package com.prisma.api.connector.mysql.database

import com.prisma.api.connector.mysql.database.DatabaseQueryBuilder.{ResultListTransform, ResultTransform}
import com.prisma.api.connector.{DataItem, QueryArguments, ResolverResult, ScalarListValue}
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.{InvalidFirstArgument, InvalidLastArgument, InvalidSkipArgument}
import slick.jdbc.SQLActionBuilder

object QueryArgumentsExtensions {
  import SlickExtensions._
  import slick.jdbc.MySQLProfile.api._
  val MAX_NODE_COUNT = 1000

  implicit class QueryArgumentsExtensions(val queryArguments: QueryArguments) extends AnyVal {
    def skip    = queryArguments.skip
    def after   = queryArguments.after
    def first   = queryArguments.first
    def before  = queryArguments.before
    def last    = queryArguments.last
    def filter  = queryArguments.filter
    def orderBy = queryArguments.orderBy

    def isReverseOrder = last.isDefined

    // The job of these methods is to return dynamically generated conditions or commands, but without the corresponding
    // keyword. For example "extractWhereConditionCommand" should return something line "q = 3 and z = '7'", without the
    // "where" keyword. This is because we might need to combine these commands with other commands. If nothing is to be
    // returned, DO NOT return an empty string, but None instead.

    def extractOrderByCommandForLists(projectId: String, modelId: String, defaultOrderShortcut: Option[String] = None): Option[SQLActionBuilder] = {

      if (first.isDefined && last.isDefined) throw APIErrors.InvalidConnectionArguments()

      // The limit instruction only works from up to down. Therefore, we have to invert order when we use before.
      val defaultOrder = "asc"
      val (order, idOrder) = isReverseOrder match {
        case true  => (invertOrder(defaultOrder), "desc")
        case false => (defaultOrder, "asc")
      }

      val nodeIdField   = s"`$projectId`.`$modelId`.`nodeId`"
      val positionField = s"`$projectId`.`$modelId`.`position`"

      //always order by nodeId, then positionfield ascending
      Some(sql"#$nodeIdField #$order, #$positionField #$idOrder")
    }

    def extractOrderByCommand(projectId: String, modelId: String, defaultOrderShortcut: Option[String] = None): Option[SQLActionBuilder] = {

      if (first.isDefined && last.isDefined) {
        throw APIErrors.InvalidConnectionArguments()
      }

      // The limit instruction only works from up to down. Therefore, we have to invert order when we use before.
      val defaultOrder = orderBy.map(_.sortOrder.toString).getOrElse("asc")
      val (order, idOrder) = isReverseOrder match {
        case true  => (invertOrder(defaultOrder), "desc")
        case false => (defaultOrder, "asc")
      }

      val idField = s"`$projectId`.`$modelId`.`id`"

      val res = orderBy match {
        case Some(orderByArg) if orderByArg.field.name != "id" =>
          val orderByField = s"`$projectId`.`$modelId`.`${orderByArg.field.name}`"

          // First order by the orderByField, then by id to break ties
          Some(sql"#$orderByField #$order, #$idField #$idOrder")

        case _ =>
          // be default, order by id. For performance reason use the id in the relation table
          Some(sql"#${defaultOrderShortcut.getOrElse(idField)} #$order")

      }
      res
    }

    def extractLimitCommand(projectId: String, modelId: String, maxNodeCount: Int = MAX_NODE_COUNT): Option[SQLActionBuilder] = {

      (first, last, skip) match {
        case (Some(first), _, _) if first < 0 => throw InvalidFirstArgument()
        case (_, Some(last), _) if last < 0   => throw InvalidLastArgument()
        case (_, _, Some(skip)) if skip < 0   => throw InvalidSkipArgument()
        case _ =>
          val count: Option[Int] = last.isDefined match {
            case true  => last
            case false => first
          }
          // Increase by 1 to know if we have a next page / previous page for relay queries
          val limitedCount: String = count match {
            case None                        => maxNodeCount.toString
            case Some(x) if x > maxNodeCount => throw APIErrors.TooManyNodesRequested(x)
            case Some(x)                     => (x + 1).toString
          }
          Some(sql"${skip.getOrElse(0)}, #$limitedCount")
      }
    }

    // If order is inverted we have to reverse the returned data items. We do this in-mem to keep the sql query simple.
    // Also, remove excess items from limit + 1 queries and set page info (hasNext, hasPrevious).
    def extractResultTransform(projectId: String, modelId: String): ResultTransform = (list: List[DataItem]) => { generateResultTransform(list) }

    def extractListResultTransform(projectId: String, modelId: String): ResultListTransform =
      (listValues: List[ScalarListValue]) => {
        val list = listValues.map(listValue => DataItem(id = listValue.nodeId, userData = Map("value" -> Some(listValue.value))))
        generateResultTransform(list)
      }

    private def generateResultTransform(list: List[DataItem]) = {
      val items = isReverseOrder match {
        case true  => list.reverse
        case false => list
      }

      (first, last) match {
        case (Some(f), _) if items.size > f => ResolverResult(items.dropRight(1), hasNextPage = true)
        case (_, Some(l)) if items.size > l => ResolverResult(items.tail, hasPreviousPage = true)
        case _                              => ResolverResult(items)
      }
    }

    def dropExtraLimitItem[T](items: Vector[T]): Vector[T] = (first, last) match {
      case (Some(f), _) if items.size > f => items.dropRight(1)
      case (_, Some(l)) if items.size > l => items.tail
      case _                              => items
    }

    def hasNext(count: Int): Boolean = (first, last) match {
      case (Some(f), _) if count > f => true
      case (_, _)                    => false
    }

    def hasPrevious(count: Int): Boolean = (first, last) match {
      case (_, Some(l)) if count > l => true
      case (_, _)                    => false
    }

    def extractWhereConditionCommand(projectId: String, modelId: String): Option[SQLActionBuilder] = {

      if (first.isDefined && last.isDefined) throw APIErrors.InvalidConnectionArguments()

      val standardCondition = filter match {
        case Some(filterArg) => generateFilterConditions(projectId, modelId, filterArg)
        case None            => None
      }

      val cursorCondition = buildCursorCondition(projectId, modelId, standardCondition)

      cursorCondition match {
        case None                     => standardCondition
        case Some(cursorConditionArg) => Some(cursorConditionArg)
      }
    }

    def invertOrder(order: String) = order.trim().toLowerCase match {
      case "desc" => "asc"
      case "asc"  => "desc"
      case _      => throw new IllegalArgumentException
    }

    // This creates a query that checks if the id is in a certain set returned by a subquery Q.
    // The subquery Q fetches all the ID's defined by the cursors and order.
    // On invalid cursor params, no error is thrown. The result set will just be empty.
    def buildCursorCondition(projectId: String, modelId: String, injectedFilter: Option[SQLActionBuilder]): Option[SQLActionBuilder] = {
      // If both params are empty, don't generate any query.
      if (before.isEmpty && after.isEmpty) return None

      val idField = s"`$projectId`.`$modelId`.`id`"

      // First, we fetch the ordering for the query. If none is passed, we order by id, ascending.
      // We need that since before/after are dependent on the order.
      val (orderByField, sortDirection) = orderBy match {
        case Some(orderByArg) => (s"`$projectId`.`$modelId`.`${orderByArg.field.name}`", orderByArg.sortOrder.toString)
        case None             => (idField, "asc")
      }

      // Then, we select the comparison operation and construct the cursors. For instance, if we use ascending order, and we want
      // to get the items before, we use the "<" comparator on the column that defines the order.
      def cursorFor(cursor: String, cursorType: String): Option[SQLActionBuilder] = {
        val compOperator = (cursorType, sortDirection.toLowerCase.trim) match {
          case ("before", "asc")  => "<"
          case ("before", "desc") => ">"
          case ("after", "asc")   => ">"
          case ("after", "desc")  => "<"
          case _                  => throw new IllegalArgumentException
        }

        Some(sql"(#$orderByField, #$idField) #$compOperator ((select #$orderByField from `#$projectId`.`#$modelId` where #$idField = '#$cursor'), '#$cursor')")
      }

      val afterCursorFilter = after match {
        case Some(afterCursor) => cursorFor(afterCursor, "after")
        case _                 => None
      }

      val beforeCursorFilter = before match {
        case Some(beforeCursor) => cursorFor(beforeCursor, "before")
        case _                  => None
      }

      // Fuse cursor commands and injected where command
      val whereCommand = combineByAnd(List(injectedFilter, afterCursorFilter, beforeCursorFilter).flatten)

      whereCommand.map(c => sql"" concat c)
    }

    def generateFilterConditions(projectId: String, tableName: String, filter: Seq[Any]): Option[SQLActionBuilder] = {
      QueryArgumentsHelpers.generateFilterConditions(projectId, tableName, filter)
    }
  }
}
