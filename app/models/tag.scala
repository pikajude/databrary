package models

import scala.concurrent.{Future,ExecutionContext}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsObject
import macros._
import dbrary._
import dbrary.SQL._
import site._

/** All tags and their names used anywhere.
  * Immutable (create only).
  */
final class Tag private (val id : Tag.Id, val name : String) extends TableRowId[Tag] {
  def add(slot : Slot, keyword : Boolean = false)(implicit site : AuthSite) : Future[Boolean] =
    if (keyword) KeywordUse.add(this, slot)
    else TagUse.add(this, slot)
  def remove(slot : Slot, keyword : Boolean = false)(implicit site : AuthSite) : Future[Boolean] =
    if (keyword) KeywordUse.remove(this, slot)
    else TagUse.remove(this, slot)
  def weight(slot : Slot) : Future[TagWeight] =
    TagWeight.getSlot(this, slot)
  def containers(implicit site : Site) : Future[Seq[TagWeightContainer]] =
    TagWeightContainer.get(this)

  def json : JsonRecord = JsonRecord(name)
  def json(options : JsonOptions.Options)(implicit site : Site) : Future[JsonRecord] =
    JsonOptions(json, options,
      "containers" -> (opt => containers.map(JsonArray.map(_.json)))
    )
}

object Tag extends TableId[Tag]("tag") {
  private[models] val row = Columns(
      SelectColumn[Id]("id")
    , SelectColumn[String]("name")
    ).map { (id, name) =>
      new Tag(id, name)
    }

  /** Retrieve an individual tag by id. */
  private[models] def get(id : Id) : Future[Option[Tag]] =
    row.SELECT(sql"WHERE id = $id").singleOpt

  private def _get(name : String)(implicit dbc : Site.DB, exc : ExecutionContext) : Future[Option[Tag]] =
    row.SELECT(sql"WHERE name = $name")(dbc, exc).singleOpt

  private val validPattern = """\p{Lower}[-\p{Lower} ]{1,30}\p{Lower}""".r.pattern
  def isValid(name : String) : Boolean = validPattern.matcher(name).matches

  private val validRegex = """ *(\p{Alpha}[-\p{Alpha} ]{1,30}\p{Alpha}) *""".r
  /** Determine if the given tag name is valid.
    * @return the normalized name if valid */
  private[models] def validate(name : String) : Option[String] = name match
    { case validRegex(tag) => Some(tag.toLowerCase)
      case _ => None
    }

  /** Retrieve an individual tag by name. */
  def get(name : String) : Future[Option[Tag]] =
    validate(name).fold[Future[Option[Tag]]](async(None))(_get(_))

  /** Search for all tags names starting with the given string. */
  def search(name : String) : Future[Seq[Tag]] =
    validate(name).fold[Future[Seq[Tag]]](async(Nil)) { name =>
      row.SELECT(sql"WHERE name LIKE ${name+"%"}").list
    }

  /** Retrieve or, if none exists, create an individual tag by name. */
  private[models] def getOrCreate(name : String) : Future[Tag] =
    sql"SELECT get_tag($name)"
    .run.single(SQL.Cols[Id].map(new Tag(_, name)))
}

private[models] object TagUse extends Table[Unit]("tag_use") {
  private[models] def remove(tag : Tag, slot : Slot)(implicit site : AuthSite) : Future[Boolean] =
    DELETE('tag -> tag.id, 'container -> slot.containerId, 'segment -> slot.segment, 'who -> site.account.id).execute

  private[models] def add(tag : Tag, slot : Slot)(implicit site : AuthSite) : Future[Boolean] =
    INSERT('tag -> tag.id, 'container -> slot.containerId, 'segment -> slot.segment, 'who -> site.account.id).execute
}

private[models] object KeywordUse extends Table[Unit]("keyword_use") {
  private[models] def remove(tag : Tag, slot : Slot) : Future[Boolean] =
    DELETE('tag -> tag.id, 'container -> slot.containerId, 'segment -> slot.segment).execute

  private[models] def add(tag : Tag, slot : Slot)(implicit site : AuthSite) : Future[Boolean] =
    INSERT('tag -> tag.id, 'container -> slot.containerId, 'segment -> slot.segment, 'who -> site.account.id).execute
}

sealed class TagWeight protected (val tag : Tag, val weight : Int, val keyword : Boolean, val vote : Boolean) {
  def json : JsonRecord = tag.json ++ JsonObject.flatten(
    Some('weight -> weight),
    if (keyword) Some('keyword -> keyword) else None,
    if (vote) Some('vote -> vote) else None
  )
}

/** Summary representation of tag information for a single tag and current user. */
final class TagWeightContainer private (tag : Tag, val container : Container, weight : Int, keyword : Boolean, vote : Boolean)
  extends TagWeight(tag, weight, keyword, vote) {
  override def json = container.json ++ super.json
}

private[models] sealed abstract class TagWeightView[T <: TagWeight] extends Table[T]("tag_weight") {
  protected def aggregateColumns(implicit site : Site) = Columns(
      SelectAs[Int]("count(tag_use.*)::integer", "weight")
    , SelectAs[Boolean]("COALESCE(bool_or(tag_use.tableoid = " + KeywordUse.tableOID + "), false)", "keyword")
    , SelectAs[Boolean](("COALESCE(bool_or(tag_use.tableoid = " + TagUse.tableOID) +: sql" AND tag_use.who = ${site.identity.id}), false)", "vote")
    )(FromTable("tag_use"))

  protected val groupColumn : SelectColumn[_]
  private[this] def groupBy = groupColumn.fromTable(FromTable("tag_use"))
  protected def columns(query : Statement)(implicit site : Site) =
    aggregateColumns.fromTable
    .fromQuery((aggregateColumns ~+ groupBy).statement + " " ++ query ++ (" GROUP BY " +: groupBy))
}

object TagWeight extends TagWeightView[TagWeight] {
  private def row(tag : Tag)(implicit site : Site) =
    aggregateColumns
    .map { (weight, keyword, up) =>
      new TagWeight(tag, weight, keyword, up)
    }

  protected val groupColumn = SelectColumn[Tag.Id]("tag")
  private def rows(query : Statement = EmptyStatement)(limit : Int)(implicit site : Site) =
    columns(query)
    .join(Tag.row on "tag_weight.tag = tag.id")
    .map { case ((weight, keyword, up), tag) =>
      new TagWeight(tag, weight, keyword, up)
    }
    .SELECT(sql"ORDER BY keyword DESC, weight DESC LIMIT $limit")
    .list

  private[models] def getSlot(tag : Tag, slot : Slot) =
    row(tag)(slot.site)
    .SELECT(sql"WHERE tag_use.tag = ${tag.id} AND tag_use.container = ${slot.containerId} AND tag_use.segment && ${slot.segment}::segment")
    .single

  /** Summarize all tags that overlap the given slot. */
  private[models] def getSlot(slot : Slot, limit : Int = 64) =
    rows(sql"WHERE tag_use.container = ${slot.containerId} AND tag_use.segment && ${slot.segment}::segment")(limit)(slot.site)

  private[models] def getVolume(volume : Volume, limit : Int = 32) =
    rows(sql"JOIN container ON tag_use.container = container.id WHERE container.volume = ${volume.id}")(limit)(volume.site)

  def getAll(limit : Int = 16)(implicit site : Site) =
    rows()(limit)
}

object TagWeightContainer extends TagWeightView[TagWeightContainer] {
  protected val groupColumn = SelectColumn[Container.Id]("container")
  private def rows(tag : Tag, query : Statement = EmptyStatement)(implicit site : Site) =
    columns(query)
    .join(Container.row on "tag_weight.container = container.id")
    .map { case ((weight, keyword, up), container) =>
      new TagWeightContainer(tag, container, weight, keyword, up)
    }
    .SELECT(sql"WHERE " + Volume.condition + " ORDER BY keyword DESC, weight DESC")
    .list

  private[models] def get(tag : Tag)(implicit site : Site) =
    rows(tag, sql"WHERE tag_use.tag = ${tag.id}")
}

final class TagCoverage private (tag : Tag, weight : Int, val coverage : Seq[Segment], val keywords : Seq[Segment], val votes : Seq[Segment])
  extends TagWeight(tag, weight, keywords.nonEmpty, votes.nonEmpty) {
  override def json = tag.json ++ JsonObject.flatten(
    Some('weight -> weight),
    Some('coverage -> coverage),
    if (keywords.nonEmpty) Some('keyword -> keywords) else None,
    if (votes.nonEmpty) Some('vote -> votes) else None
  )
}

object TagCoverage extends Table[TagCoverage]("tag_coverage") {
  private def aggregateColumns(implicit site : Site) = Columns(
      SelectAs[Int]("count(tag_use.*)::integer", "weight")
    , SelectAs[IndexedSeq[Segment]]("segments_union(tag_use.segment)", "coverage")
    , SelectAs[IndexedSeq[Segment]]("segments_union(CASE WHEN tag_use.tableoid = " + KeywordUse.tableOID + " THEN tag_use.segment ELSE 'empty' END)", "keywords")
    , SelectAs[IndexedSeq[Segment]](("segments_union(CASE WHEN tag_use.tableoid = " + TagUse.tableOID) +: sql" AND tag_use.who = ${site.identity.id} THEN tag_use.segment ELSE 'empty' END)", "votes")
    )(FromTable("tag_use"))
  private val groupColumn = SelectColumn[Tag.Id]("tag")
  private[this] def groupBy = groupColumn.fromTable(FromTable("tag_use"))
  protected def columns(query : Statement)(implicit site : Site) =
    aggregateColumns.fromTable
    .fromQuery((aggregateColumns ~+ groupBy).statement + " " ++ query ++ (" GROUP BY " +: groupBy))

  private def rows(query : Statement = EmptyStatement)(implicit site : Site) =
    columns(query)
    .join(Tag.row on "tag_coverage.tag = tag.id")
    .map { case ((weight, coverage, keywords, votes), tag) =>
      new TagCoverage(tag, weight, coverage, keywords, votes)
    }
    .SELECT(sql"ORDER BY weight DESC")
    .list

  private[models] def getSlot(slot : Slot) =
    rows(sql"WHERE tag_use.container = ${slot.containerId} AND tag_use.segment && ${slot.segment}::segment")(slot.site)
}
