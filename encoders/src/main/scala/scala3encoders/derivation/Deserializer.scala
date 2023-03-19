package scala3encoders.derivation

import scala.compiletime
import scala.deriving.Mirror
import scala.reflect.ClassTag

import org.apache.spark.sql.catalyst.expressions.{Expression, If, IsNull, Literal}
import org.apache.spark.sql.catalyst.expressions.objects.NewInstance
import org.apache.spark.sql.catalyst.analysis.UnresolvedExtractValue
import org.apache.spark.sql.catalyst.DeserializerBuildHelper.*
import org.apache.spark.sql.catalyst.expressions.objects._

import org.apache.spark.sql.types.*
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.catalyst.WalkedTypePath

trait Deserializer[T]:
  def inputType: DataType
  def deserialize(path: Expression): Expression

object Deserializer:
  given Deserializer[Double] with
    def inputType: DataType = DoubleType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Double])

  given Deserializer[Float] with
    def inputType: DataType = FloatType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Float])

  given Deserializer[Short] with
    def inputType: DataType = ShortType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Short])

  given Deserializer[Byte] with
    def inputType: DataType = ByteType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Byte])

  given Deserializer[Boolean] with
    def inputType: DataType = BooleanType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Boolean])
  
  given Deserializer[String] with
    def inputType: DataType = StringType
    def deserialize(path: Expression): Expression = 
      createDeserializerForString(path, false)

  given Deserializer[Int] with
    def inputType: DataType = IntegerType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Integer])

  given Deserializer[Long] with
    def inputType: DataType = LongType
    def deserialize(path: Expression): Expression =
      createDeserializerForTypesSupportValueOf(path, classOf[java.lang.Long])

  given instantDeserializer: Deserializer[java.time.Instant] with
    def inputType: DataType = TimestampType
    def deserialize(path: Expression): Expression =
      createDeserializerForInstant(path)
      
  given deriveOpt[T](using d: Deserializer[T], ct: ClassTag[T]): Deserializer[Option[T]] =
    new Deserializer[Option[T]]:
      override def inputType: DataType = 
        ObjectType(ct.runtimeClass)
        
      override def deserialize(path: Expression): Expression =
        val tpe = ScalaReflection.typeBoxedJavaMapping.getOrElse(d.inputType, ct.runtimeClass)
        WrapOption(d.deserialize(path), ObjectType(tpe))

  given deriveArray[T](using d: Deserializer[T], ct: ClassTag[T]): Deserializer[Array[T]] =
    // TODO: nullable. walked
    new Deserializer[Array[T]]:
      override def inputType: DataType = ArrayType(d.inputType)
      override def deserialize(path: Expression): Expression =
        val mapFunction: Expression => Expression = el =>
          deserializerForWithNullSafetyAndUpcast(
            el,
            d.inputType,
            true,
            WalkedTypePath(Nil),
            (casted, _) => d.deserialize(casted)
          )
        val arrayClass = ObjectType(ct.newArray(0).getClass)
        val arrayData = UnresolvedMapObjects(mapFunction, path)

        val methodName = d.inputType match
        // TODO: replace with scala 3 reflection?
          case IntegerType => "toIntArray"
          case LongType => "toLongArray"
          case DoubleType => "toDoubleArray"
          case FloatType => "toFloatArray"
          case ShortType => "toShortArray"
          case ByteType => "toByteArray"
          case BooleanType => "toBooleanArray"
          // non-primitive
          case _ => "array"

        Invoke(arrayData, methodName, arrayClass, returnNullable = true)

  inline given deriveSeq[F[_], T](using d: Deserializer[T], ct: ClassTag[T])(using F[T] <:< Seq[T]): Deserializer[F[T]] =
    // TODO: Nullable
    new Deserializer[F[T]]:
      override def inputType: DataType = ArrayType(d.inputType)
      override def deserialize(path: Expression): Expression =
        val mapFunction: Expression => Expression = element =>
          deserializerForWithNullSafetyAndUpcast(
            element,
            d.inputType,
            nullable = true,
            WalkedTypePath(Nil),
            (casted, _) => d.deserialize(casted)
          )
        UnresolvedMapObjects(mapFunction, path, Some(classOf[Seq[T]]))

  inline given derivedSet[T: Deserializer : ClassTag]: Deserializer[Set[T]] =
    val forSeq = deriveSeq[List, T]
    new Deserializer[Set[T]]:
      override def inputType: DataType = forSeq.inputType
      override def deserialize(path: Expression): Expression = 
        val res = forSeq.deserialize(path).asInstanceOf[UnresolvedMapObjects]
        UnresolvedMapObjects(res.function, res.child, Some(classOf[Set[T]]))        

  inline given derivedMap[K, V](using kd: Deserializer[K], vd: Deserializer[V], ct: ClassTag[Map[K, V]]): Deserializer[Map[K, V]] =
    new Deserializer[Map[K, V]]:
      override def inputType: DataType = MapType(kd.inputType, vd.inputType)
      override def deserialize(path: Expression): Expression =
        UnresolvedCatalystToExternalMap(
          path,
          kd.deserialize(_),
          vd.deserialize(_),
          ct.runtimeClass
        )

  // inspired by https://github.com/apache/spark/blob/39542bb81f8570219770bb6533c077f44f6cbd2a/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala#L356-L390
  inline given derivedProduct[T](using mirror: Mirror.ProductOf[T], classTag: ClassTag[T]): Deserializer[T] = 
    val deserializers: List[Deserializer[?]] = summonTuple[mirror.MirroredElemTypes]
    val labels: List[String] = getElemLabels[mirror.MirroredElemLabels]
    val fields = labels.zip(deserializers)
      .map((label, deserializer) => StructField(label, deserializer.inputType))
    new Deserializer[T]:
      override def inputType: StructType = StructType(fields)
      override def deserialize(path: Expression): Expression =
        val arguments = inputType.fields.toSeq
          .zip(deserializers)
          .map { (structField, deserializer) =>
            val newPath = UnresolvedExtractValue(path, Literal(structField.name))
            deserializer.deserialize(newPath)
          }
        val outputType = ObjectType(classTag.runtimeClass)
        NewInstance(outputType.cls, arguments, outputType, false)

  private inline def summonTuple[T <: Tuple]: List[Deserializer[?]] = inline compiletime.erasedValue[T] match
    case _: EmptyTuple => Nil
    case _: (t *: ts) => compiletime.summonInline[Deserializer[t]] :: summonTuple[ts]

