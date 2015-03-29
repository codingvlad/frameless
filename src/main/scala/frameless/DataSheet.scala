package frameless

import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.function.{ Function => JFunction }
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{ DataFrame, Row, SaveMode, SQLContext }
import org.apache.spark.storage.StorageLevel

import scala.collection.JavaConverters.{ asScalaBufferConverter, seqAsJavaListConverter }
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import shapeless.{ Generic, HList, LabelledGeneric }
import shapeless.ops.hlist.Prepend
import shapeless.ops.record.Values
import shapeless.ops.traversable.FromTraversable
import shapeless.syntax.std.traversable.traversableOps

/** Wrapper around [[org.apache.spark.sql.DataFrame]] where the type parameter tracks the schema.
  *
  * All heavy-lifting is still being done by the backing DataFrame so this API will more or less
  * be 1-to-1 with that of the DataFrame's.
  */
final class DataSheet[Schema <: HList] private(val dataFrame: DataFrame) {
  import DataSheet._

  def as(alias: Symbol): DataSheet[Schema] = DataSheet(dataFrame.as(alias))

  def as(alias: String): DataSheet[Schema] = DataSheet(dataFrame.as(alias))

  def cache(): this.type = {
    dataFrame.cache()
    this
  }

  def count(): Long = dataFrame.count()

  def distinct: DataSheet[Schema] = DataSheet(dataFrame.distinct)

  def except(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.except(other.dataFrame))

  def explain(): Unit = dataFrame.explain()

  def explain(extended: Boolean): Unit = dataFrame.explain(extended)

  def intersect(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.intersect(other.dataFrame))

  def isLocal: Boolean = dataFrame.isLocal

  def join[OtherSchema <: HList, NewSchema <: HList](right: DataSheet[OtherSchema])(
                                                     implicit P: Prepend.Aux[Schema, OtherSchema, NewSchema]): DataSheet[NewSchema] =
    DataSheet(dataFrame.join(right.dataFrame))

  def limit(n: Int): DataSheet[Schema] = DataSheet(dataFrame.limit(n))

  def persist(newLevel: StorageLevel): this.type = {
    dataFrame.persist(newLevel)
    this
  }

  def persist(): this.type = {
    dataFrame.persist()
    this
  }

  def printSchema(): Unit = dataFrame.printSchema()

  val queryExecution = dataFrame.queryExecution

  def registerTempTable(tableName: String): Unit = dataFrame.registerTempTable(tableName)

  def repartition(numPartitions: Int): DataSheet[Schema] = DataSheet(dataFrame.repartition(numPartitions))

  def sample(withReplacement: Boolean, fraction: Double): DataSheet[Schema] =
    DataSheet(dataFrame.sample(withReplacement, fraction))

  def sample(withReplacement: Boolean, fraction: Double, seed: Long): DataSheet[Schema] =
    DataSheet(dataFrame.sample(withReplacement, fraction, seed))

  def save(source: String, mode: SaveMode, options: Map[String, String]): Unit = dataFrame.save(source, mode, options)

  def save(path: String, source: String, mode: SaveMode): Unit = dataFrame.save(path, source, mode)

  def save(path: String, source: String): Unit = dataFrame.save(path, source)

  def save(path: String, mode: SaveMode): Unit = dataFrame.save(path, mode)

  def save(path: String): Unit = dataFrame.save(path)

  def saveAsParquetFile(path: String): Unit = dataFrame.saveAsParquetFile(path)

  def saveAsTable(tableName: String, source: String, mode: SaveMode, options: Map[String, String]): Unit =
    dataFrame.saveAsTable(tableName, source, mode, options)

  def saveAsTable(tableName: String, source: String, mode: SaveMode): Unit =
    dataFrame.saveAsTable(tableName, source, mode)

  def saveAsTable(tableName: String, source: String): Unit =
    dataFrame.saveAsTable(tableName, source)

  def saveAsTable(tableName: String, mode: SaveMode): Unit =
    dataFrame.saveAsTable(tableName, mode)

  def saveAsTable(tableName: String): Unit =
    dataFrame.saveAsTable(tableName)

  def show(): Unit = dataFrame.show()

  def show(numRows: Int): Unit = dataFrame.show(numRows)

  val sqlContext: SQLContext = dataFrame.sqlContext

  override def toString(): String = s"DataSheet:\n${dataFrame.toString}"

  def unionAll(other: DataSheet[Schema]): DataSheet[Schema] =
    DataSheet(dataFrame.unionAll(other.dataFrame))

  def unpersist(): this.type = {
    dataFrame.unpersist()
    this
  }

  def unpersist(blocking: Boolean): this.type = {
    dataFrame.unpersist(blocking)
    this
  }

  /////////////////////////

  /** Proxy that allows getting contents as a [[scala.Product]] type.
    *
    * Example usage:
    * {{{
    * case class Foo(x: Int, y: Double)
    *
    * val dataSheet = ...
    *
    * // Assuming dataSheet schema matches Foo
    * dataSheet.get[Foo].head(10): Array[Foo]
    * }}}
    */
  def get[P <: Product]: GetProxy[P] = new GetProxy[P]

  final class GetProxy[P <: Product] private[frameless] {
    def collect[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                              P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.collect().map(unsafeRowToProduct[P, V])

    def collectAsScalaList[V <: HList]()(implicit Val: Values.Aux[Schema, V],Gen: Generic.Aux[P, V],
                                         V: FromTraversable[V]): List[P] =
      dataFrame.collectAsList().asScala.toList.map(unsafeRowToProduct[P, V])

    def collectAsList[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                    V: FromTraversable[V]): java.util.List[P] =
      collectAsScalaList[V].asJava

    def first[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V], V: FromTraversable[V]): P =
      unsafeRowToProduct(dataFrame.first())

    def head[V <: HList]()(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V], V: FromTraversable[V]): P =
      unsafeRowToProduct(dataFrame.head())

    def head[V <: HList](n: Int)(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                 P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.head(n).map(unsafeRowToProduct[P, V])

    def javaRDD[V <: HList](implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                            V: FromTraversable[V]): JavaRDD[P] = {
      val f = new JFunction[Row, P] { def call(v1: Row): P = unsafeRowToProduct(v1) }
      dataFrame.javaRDD.map(f)
    }

    def rdd[V <: HList](implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                        P: ClassTag[P], V: FromTraversable[V]): RDD[P] =
      dataFrame.rdd.map(unsafeRowToProduct[P, V])

    def take[V <: HList](n: Int)(implicit Val: Values.Aux[Schema, V], Gen: Generic.Aux[P, V],
                                 P: ClassTag[P], V: FromTraversable[V]): Array[P] =
      dataFrame.take(n).map(unsafeRowToProduct[P, V])
  }

  /** Proxy that allows using combinators through [[shapeless.HList]] interface.
    *
    * Example usage:
    * {{{
    * val dataSheet: DataSheet[Int :: Double :: List[Byte] :: HNil] = ...
    *
    * def foo(i: Int, bs: List[Byte]): Long = ...
    *
    * dataSheet.combinator.map(hl => foo(hl(0), hl(2)))
    * }}}
    */
  def combinator[V <: HList](implicit Val: Values.Aux[Schema, V]): CombinatorProxy[V] =
    new CombinatorProxy[V]

  final class CombinatorProxy[V <: HList] private[frameless] {
    def flatMap[R](f: V => TraversableOnce[R])(implicit R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
      dataFrame.flatMap(f.compose(unsafeRowToHList[V]))

    def foreach[R](f: V => Unit)(implicit V: FromTraversable[V]): Unit =
      dataFrame.foreach(f.compose(unsafeRowToHList[V]))

    def foreachPartition(f: Iterator[V] => Unit)(implicit V: FromTraversable[V]): Unit =
      dataFrame.foreachPartition(f.compose(_.map(unsafeRowToHList[V])))

    def map[R](f: V => R)(implicit R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
      dataFrame.map(f.compose(unsafeRowToHList[V]))

    def mapPartitions[R](f: Iterator[V] => Iterator[R])(implicit R: ClassTag[R], V: FromTraversable[V]): RDD[R] =
      dataFrame.mapPartitions(f.compose(_.map(unsafeRowToHList[V])))
  }
}

object DataSheet {
  private def apply[Schema <: HList](dataFrame: DataFrame): DataSheet[Schema] =
    new DataSheet[Schema](dataFrame)

  private def unsafeRowToHList[L <: HList : FromTraversable](row: Row): L =
    row.toSeq.toHList[L].get

  private def unsafeRowToProduct[P <: Product, L <: HList](row: Row)(
                                                           implicit Gen: Generic.Aux[P, L], L: FromTraversable[L]): P =
    Gen.from(unsafeRowToHList(row))

  def fromRDD[P <: Product : TypeTag, Schema <: HList](rdd: RDD[P])(
                                                       implicit Gen: LabelledGeneric.Aux[P, Schema]): DataSheet[Schema] =
    DataSheet(new SQLContext(rdd.sparkContext).implicits.rddToDataFrameHolder(rdd).toDF())
}
