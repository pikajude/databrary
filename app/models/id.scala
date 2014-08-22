package models

import play.api.data.format.{Formats,Formatter}
import play.api.mvc.{PathBindable,QueryStringBindable}
import play.api.libs.json
import dbrary._

private[models] abstract trait HasGenericId[I,+T] extends Equals {
  def _id : I
  def ===[X >: T](i : HasGenericId[I,X]) : Boolean = _id == i._id
  /** Equality based on id value.
    * This doesn't properly check types due to erasure, so === should be preferred. */
  override def equals(i : Any) = i match {
    case i : HasGenericId[I,T] if i canEqual this => _id equals i._id
    case _ => false
  }
  def canEqual(i : Any) = i.isInstanceOf[HasGenericId[I,T]]
  // this is necessary for match?
  // def ==(i : HasGenericId[I,_]) = _id == i._id
  override def hashCode = _id.hashCode
  override def toString = super.toString + "(" + _id + ")"
}

/** Wrap identifiers and tag them with a particular type. This is primarily useful to tag primary keys with a specific type corresponding to the source table.
  * @tparam I the type of the identifier
  * @tparam T the tag
  */
private[models] abstract class GenericId[I,+T] protected (val _id : I) extends HasGenericId[I,T] {
  override def toString = _id.toString
}

private[models] trait HasId[+T] extends HasGenericId[Int,T] {
  // def id : IntId[T] = IntId[T](_id)
  override def hashCode = _id
}
/** GenericId specific to integers.  The most common (only?) type of identifier we have. */
final class IntId[+T] private (id : Int) extends GenericId[Int,T](id) with HasId[T] {
  // override def id = this
  def formatted(s : String) = _id.formatted(s)
}
private[models] object IntId {
  private[models] def apply[T](i : Int) = new IntId[T](i)
  // The normal family of conversions for database and web i/o:
  implicit def pathBindable[T] : PathBindable[IntId[T]] = PathBindable.bindableInt.transform(apply[T] _, _._id)
  implicit def queryStringBindable[T] : QueryStringBindable[IntId[T]] = QueryStringBindable.bindableInt.transform(apply[T] _, _._id)
  implicit def sqlType[T] : SQLType[IntId[T]] = SQLType.transform[Int,IntId[T]]("integer", classOf[IntId[T]])(i => Some(apply[T](i)), _._id)
  implicit def formatter[T] : Formatter[IntId[T]] = new Formatter[IntId[T]] {
    def bind(key : String, data : Map[String, String]) =
      Formats.intFormat.bind(key, data).right.map(apply _)
    def unbind(key : String, value : IntId[T]) =
      Formats.intFormat.unbind(key, value._id)
  }
  implicit def jsonWrites[T] : json.Writes[IntId[T]] = new json.Writes[IntId[T]] {
    def writes(i : IntId[T]) = json.JsNumber(i._id)
  }
}
/** Any class (usually a singleton object) which provides an Id type. */
private[models] trait ProvidesId[T] {
  type Id = IntId[T]
  /** Create an [[Id]] value. */
  def asId(i : Int) : Id = IntId[T](i)
}

private[models] trait HasLongId[+T] extends HasGenericId[Long,T] {
  def id : LongId[T] = LongId[T](_id)
}
/** GenericId specific to longs. */
final class LongId[+T] private (id : Long) extends GenericId[Long,T](id) with HasLongId[T] {
  override def id = this
  def formatted(s : String) = _id.formatted(s)
}
private[models] object LongId {
  private[models] def apply[T](i : Long) = new LongId[T](i)
  // The normal family of conversions for database and web i/o:
  implicit def pathBindable[T] : PathBindable[LongId[T]] = PathBindable.bindableLong.transform(apply[T] _, _._id)
  implicit def queryStringBindable[T] : QueryStringBindable[LongId[T]] = QueryStringBindable.bindableLong.transform(apply[T] _, _._id)
  implicit def sqlType[T] : SQLType[LongId[T]] = SQLType.transform[Long,LongId[T]]("bigint", classOf[LongId[T]])(i => Some(apply[T](i)), _._id)
  implicit def formatter[T] : Formatter[LongId[T]] = new Formatter[LongId[T]] {
    def bind(key : String, data : Map[String, String]) =
      Formats.longFormat.bind(key, data).right.map(apply _)
    def unbind(key : String, value : LongId[T]) =
      Formats.longFormat.unbind(key, value._id)
  }
  implicit def jsonWrites[T] : json.Writes[LongId[T]] = new json.Writes[LongId[T]] {
    def writes(i : LongId[T]) = json.JsNumber(i._id)
  }
}
