package org.apache.spark.sql.sources.druid

import org.apache.spark.sql.catalyst.analysis.TypeCoercion
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Expand}
import org.apache.spark.sql.types._
import org.rzlabs.druid._
import org.rzlabs.druid.jscodegen.{JSAggrGenerator, JSCodeGenerator}
import org.rzlabs.druid.metadata.DruidRelationColumn

trait AggregateTransform {
  self: DruidPlanner =>

  /**
   * Collect the [[AggregateExpression]]s in ''aggregateExpressions''
   * of [[Aggregate]] operator.
   * @param aggrExprs The aggregateExpressions of Aggregate.
   * @return The collected AggregateExpressions.
   */
  def aggExpressions(aggrExprs: Seq[NamedExpression]): Seq[AggregateExpression] = {
    aggrExprs.flatMap(_ collect { case a: AggregateExpression => a }).distinct
  }

  def addCountAgg(dqb: DruidQueryBuilder, aggrExpr: AggregateExpression) = {
    val outputName = dqb.nextAlias

    // 'Count' is a implicit metric can be applied 'count' operator on.
    dqb.aggregationSpec(new CountAggregationSpec(outputName, "count")).
      outputAttribute(outputName, aggrExpr, aggrExpr.dataType, LongType)
  }

  private def setAggregationInfo(dqb: DruidQueryBuilder, aggrExpr: AggregateExpression) = {

    (dqb, aggrExpr, aggrExpr.aggregateFunction) match {
      case (_, _, Count(Seq(Literal(1, IntegerType)))) |
           (_, _, Count(Seq(AttributeReference("1", _, _, _)))) =>
        Some(addCountAgg(dqb, aggrExpr))
      case (_, ae, fn) if JSAggrGenerator.jsAvgCandidate(dqb, fn) =>
        // Based on the same reason (cannot know the denominator metric)
        // we just throw a DruidDataSourceException.
        throw new DruidDataSourceException(s"${fn.toAggString(false)} calculation may " +
          s"not be finished correctly, because we do not know the metric specified as 'count' type " +
          s"at indexing time and the 'longSum' of which will be the denominator of the Average function.")
      case DruidNativeAggregator(dqb1) => Some(dqb1)
      case (_, _, fn) =>
        for (jsdqb <- JSAggrGenerator.jsAggr(dqb, aggrExpr, fn,
          dqb.druidRelationInfo.options.timeZoneId)) yield
          jsdqb._1

    }
  }

  private def setGroupingInfo(dqb: DruidQueryBuilder,
                              timeElemExtractor: SparkNativeTimeElementExtractor,
                              grpExpr: Expression
                             ): Option[DruidQueryBuilder] = {

    grpExpr match {
      case AttributeReference(nm, dataType, _, _) if dqb.isNonTimeDimension(nm) =>
        val dc = dqb.druidColumn(nm).get
        if (dc.isDimension()) {
          Some(dqb.dimensionSpec(new DefaultDimensionSpec(dc.name, nm)).outputAttribute(nm,
            grpExpr, dataType, DruidDataType.sparkDataType(dc.dataType)))
        } else if (dc.isNotIndexedDimension) {
          throw new DruidDataSourceException(s"Column '${dc.name}' is not indexed into datasource.")
        } else {
          None
        }
      case timeElemExtractor(dtGrp) =>
        val timeFmtExtractFunc: ExtractionFunctionSpec = {
          if (dtGrp.inputFormat.isDefined) {
            new TimeParsingExtractionFunctionSpec(dtGrp.inputFormat.get, dtGrp.formatToApply)
          } else {
            new TimeFormatExtractionFunctionSpec(dtGrp.formatToApply, dtGrp.timeZone.getOrElse(null))
          }
        }
        Some(dqb.dimensionSpec(
          new ExtractionDimensionSpec(dtGrp.druidColumn.name, timeFmtExtractFunc, dtGrp.outputName))
            .outputAttribute(dtGrp.outputName, grpExpr, grpExpr.dataType,
              DruidDataType.sparkDataType(dtGrp.druidColumn.dataType)))
      case _ =>
        val codeGen = JSCodeGenerator(dqb, grpExpr, false, false,
          dqb.druidRelationInfo.options.timeZoneId)
        for (fn <- codeGen.fnCode) yield {
          val outDName = dqb.nextAlias
          dqb.dimensionSpec(new ExtractionDimensionSpec(codeGen.fnParams.last,
            new JavascriptExtractionFunctionSpec(fn), outDName)).
            outputAttribute(outDName, grpExpr, grpExpr.dataType, StringType)
        }
    }
  }

  private def transformGrouping(dqb: DruidQueryBuilder,
                                   aggOp: Aggregate,
                                   grpExprs: Seq[Expression],
                                   aggrExprs: Seq[NamedExpression]
                                  ): Option[DruidQueryBuilder] = {

    val timeElemExtractor = new SparkNativeTimeElementExtractor()(dqb)

    val dqb1 = grpExprs.foldLeft(Some(dqb).asInstanceOf[Option[DruidQueryBuilder]]) {
      (odqb, e) => odqb.flatMap(setGroupingInfo(_, timeElemExtractor, e))
    }

    // all AggregateExpressions in agregateExpressions list.
    val allAggrExprs = aggExpressions(aggrExprs)

    val dqb2 = allAggrExprs.foldLeft(dqb1) {
      (dqb, ae) => dqb.flatMap(setAggregationInfo(_, ae))
    }

    dqb2
  }

  private def attrRefName(e: Expression): Option[String] = {
    e match {
      case AttributeReference(nm, _, _, _) => Some(nm)
      case Cast(AttributeReference(nm, _, _, _), _) => Some(nm)
      case Alias(AttributeReference(nm, _, _, _), _) => Some(nm)
      case _ => None
    }
  }

  private object DruidNativeAggregator {

    def unapply(t: (DruidQueryBuilder, AggregateExpression, AggregateFunction)):
    Option[DruidQueryBuilder] = {
      val dqb = t._1
      val aggrExpr = t._2
      val aggrFunc = t._3
      val outputName = dqb.nextAlias
      (dqb, aggrFunc, outputName) match {
        case ApproximateCountDistinctAggregate(aggrSpec) =>
          Some(dqb.aggregationSpec(aggrSpec).
            outputAttribute(outputName, aggrExpr, aggrExpr.dataType, LongType))

        case SumMinMaxFirstLastAggregate(dc, aggrSpec) =>
          Some(dqb.aggregationSpec(aggrSpec).
            outputAttribute(outputName, aggrExpr, aggrExpr.dataType,
              DruidDataType.sparkDataType(dc.dataType)))

        case AvgAggregate(dqb1, sumAlias, countAlias) =>
          Some(dqb1.avgExpression(aggrExpr, sumAlias, countAlias))

        case _ => None
      }
    }
  }

  private object AvgAggregate {

    def unapply(t: (DruidQueryBuilder, AggregateFunction, String)):
    Option[(DruidQueryBuilder, String, String)] = {
      val dqb = t._1
      val aggrFunc = t._2
      val outputName = t._3
      val r = for (c <- aggrFunc.children.headOption if aggrFunc.children.size == 1;
                   columnName <- attrRefName(c);
                   dc <- dqb.druidColumn(columnName) if dc.isMetric;
                   cdt <- Some(DruidDataType.sparkDataType(dc.dataType));
                   dt <- TypeCoercion.findTightestCommonTypeOfTwo(aggrFunc.dataType, cdt)
      ) yield (aggrFunc, dt, dc, outputName)

      r.flatMap {
        // count may not be the count metric!!!
//        case (_: Average, dt, dc, outputName)
//          if (dqb.druidRelationInfo.druidColumns.exists(_ == "count")) =>
//          val outputName2 = dqb.nextAlias
//          val druidAggrFunc = dc.dataType match {
//            case DruidDataType.Long => "longSum"
//            case _ => "doubleSum"
//          }
//          val aggrFuncDataType = DruidDataType.sparkDataType(dc.dataType)
//          Some((dqb.aggregationSpec(SumAggregationSpec(druidAggrFunc, outputName, dc.name)).
//            outputAttribute(outputName, null, aggrFuncDataType, aggrFuncDataType).
//            aggregationSpec(SumAggregationSpec("longSum", outputName2, "count")).
//            outputAttribute(outputName2, null, LongType, LongType), outputName, outputName2))
        case (fn: Average, _, _, _) =>
          throw new DruidDataSourceException(s"${fn.toAggString(false)} calculation may " +
            s"not be finished correctly, because we do not know the metric specified as 'count' type " +
            s"at indexing time and the 'longSum' of which will be the denominator of the Average function.")
        case _ => None
      }
    }
  }

  private object SumMinMaxFirstLastAggregate {

    def unapply(t: (DruidQueryBuilder, AggregateFunction, String)):
    Option[(DruidRelationColumn, AggregationSpec)] = {
      val dqb = t._1
      val aggrFunc = t._2
      val outputName = t._3
      val r = for (c <- aggrFunc.children.headOption if aggrFunc.children.size == 1;
                   columnName <- attrRefName(c);
                   dc <- dqb.druidColumn(columnName) if dc.isMetric;
                   cdt <- Some(DruidDataType.sparkDataType(dc.dataType));
                   dt <- TypeCoercion.findTightestCommonTypeOfTwo(aggrFunc.dataType, cdt)
                   ) yield
        (aggrFunc, dt, dc, outputName)

      r.flatMap {
        case (_: Sum, LongType, dc, outputName) =>
          Some(dc -> SumAggregationSpec("longSum", outputName, dc.name))
        case (_: Sum, FloatType, dc, outputName) =>
          Some(dc -> SumAggregationSpec("floatSum", outputName, dc.name))
        case (_: Sum, DoubleType, dc, outputName) =>
          Some(dc -> SumAggregationSpec("doubleSum", outputName, dc.name))
        case (_: Min, LongType, dc, outputName) =>
          Some(dc -> MinAggregationSpec("longMin", outputName, dc.name))
        case (_: Min, FloatType, dc, outputName) =>
          Some(dc -> MinAggregationSpec("floatMin", outputName, dc.name))
        case (_: Min, DoubleType, dc, outputName) =>
          Some(dc -> MinAggregationSpec("doubleMin", outputName, dc.name))
        case (_: Max, LongType, dc, outputName) =>
          Some(dc -> MaxAggregationSpec("longMax", outputName, dc.name))
        case (_: Max, FloatType, dc, outputName) =>
          Some(dc -> MaxAggregationSpec("floatMax", outputName, dc.name))
        case (_: Max, DoubleType, dc, outputName) =>
          Some(dc -> MaxAggregationSpec("doubleMax", outputName, dc.name))
        case (_: First, LongType, dc, outputName) =>
          Some(dc -> FirstAggregationSpec("longFirst", outputName, dc.name))
        case (_: First, FloatType, dc, outputName) =>
          Some(dc -> FirstAggregationSpec("floatFirst", outputName, dc.name))
        case (_: First, DoubleType, dc, outputName) =>
          Some(dc -> FirstAggregationSpec("doubleFirst", outputName, dc.name))
        case (_: Last, LongType, dc, outputName) =>
          Some(dc -> LastAggregationSpec("longLast", outputName, dc.name))
        case (_: Last, FloatType, dc, outputName) =>
          Some(dc -> LastAggregationSpec("floatLast", outputName, dc.name))
        case (_: Last, DoubleType, dc, outputName) =>
          Some(dc -> LastAggregationSpec("doubleLast", outputName, dc.name))
        case _ => None
      }
    }
  }

  private def isHyperUniqueAggregator(dqb: DruidQueryBuilder, dc: DruidRelationColumn): Boolean = {
    val aggregators = dqb.druidRelationInfo.druidDataSource.aggregators
    if (dc.hasHllMetric) {
      aggregators.map { aggrs =>
        aggrs.find(_ == dc.hllMetric.get.name).map { aggr =>
          DruidDataType.withName(aggr._2.`type`) == DruidDataType.HyperUnique
        }.getOrElse(false)
      }.getOrElse { // Have no aggregators info got from MetadataResponse.
        // We do not retain the hyperUnique or thetaSketch metric columns.
        !dqb.druidRelationInfo.druidColumns.exists(_._1 == dc.hllMetric.get.name)
      }
    } else false
  }

  private def isThetaSketchAggregator(dqb: DruidQueryBuilder, dc: DruidRelationColumn): Boolean = {
    val aggregators = dqb.druidRelationInfo.druidDataSource.aggregators
    if (dc.hasSketchMetric) {
      aggregators.map { aggrs =>
        aggrs.find(_ == dc.sketchMetric.get.name).map { aggr =>
          DruidDataType.withName(aggr._2.`type`) == DruidDataType.ThetaSketch
        }.getOrElse(false)
      }.getOrElse { // Have no aggregators info got from MetadataResponse.
        // We do not retain the hyperUnique or thetaSketch metric columns.
        !dqb.druidRelationInfo.druidColumns.exists(_._1 == dc.hllMetric.get.name)
      }
    } else false
  }

  private object ApproximateCountDistinctAggregate {

    def unapply(t: (DruidQueryBuilder, AggregateFunction, String)): Option[AggregationSpec] = {
      val dqb = t._1
      val aggFunc = t._2
      val outputName = t._3
      // Druid's aggregators only accept one argument.
      val r = for (c <- aggFunc.children.headOption if aggFunc.children.size == 1;
                   columnName <- attrRefName(c);
                   dc <- dqb.druidColumn(columnName)
                   if dc.isDimension(excludeTime = true) || dc.hasHllMetric) yield
        (aggFunc, dc, outputName)
      // TODO: Sketch supports.
      r.flatMap {
        case (_: HyperLogLogPlusPlus, dc, outputName) if isHyperUniqueAggregator(dqb, dc) =>
          Some(new HyperUniqueAggregationSpec(outputName, dc.hllMetric.get.name))
        case (_: HyperLogLogPlusPlus, dc, outputName) if isThetaSketchAggregator(dqb, dc) =>
          Some(new SketchAggregationSpec(outputName, dc.sketchMetric.get.name))
        case (_: HyperLogLogPlusPlus, dc, outputName) =>
          Some(new CardinalityAggregationSpec(outputName, List(dc.name)))
        case _ => None // not approximate count distinct aggregation
      }
    }
  }

  val aggregateTransform: DruidTransform = {

    case (dqb, Aggregate(_, _, Aggregate(_, _, Expand(_, _, _)))) =>
      // There are more than 1 distinct aggregate expressions.
      // Because Druid cannot handle accurate distinct operation,
      // so we do not push aggregation down to Druid.
      Nil
    case (dqb, agg @ Aggregate(grpExprs, aggrExprs, child)) =>
      // There is 1 distinct aggregate expressions.
      // Because Druid cannot handle accurate distinct operation,
      // so we do not push aggregation down to Druid.
      if (aggrExprs.exists {
        case ne: NamedExpression => ne.find {
          case ae: AggregateExpression if ae.isDistinct => true
          case _ => false
        }.isDefined
      }) Nil else {
        // There is no distinct aggregate expressions.
        // Returns Nil if plan returns Nil.
        plan(dqb, child).flatMap { dqb =>
          transformGrouping(dqb, agg, grpExprs, aggrExprs)
        }
      }
  }
}