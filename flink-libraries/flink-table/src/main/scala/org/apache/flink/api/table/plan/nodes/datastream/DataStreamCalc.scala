/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.table.plan.nodes.datastream

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.calcite.rex.RexProgram
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.table.StreamTableEnvironment
import org.apache.flink.api.table.codegen.CodeGenerator
import org.apache.flink.api.table.plan.nodes.FlinkCalc
import org.apache.flink.api.table.typeutils.TypeConverter._
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.streaming.api.datastream.DataStream

/**
  * Flink RelNode which matches along with FlatMapOperator.
  *
  */
class DataStreamCalc(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    input: RelNode,
    rowRelDataType: RelDataType,
    calcProgram: RexProgram,
    ruleDescription: String)
  extends SingleRel(cluster, traitSet, input)
  with FlinkCalc
  with DataStreamRel {

  override def deriveRowType() = rowRelDataType

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new DataStreamCalc(
      cluster,
      traitSet,
      inputs.get(0),
      getRowType,
      calcProgram,
      ruleDescription
    )
  }

  override def toString: String = calcToString(calcProgram, getExpressionString)

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw)
      .item("select", selectionToString(calcProgram, getExpressionString))
      .itemIf("where",
        conditionToString(calcProgram, getExpressionString),
        calcProgram.getCondition != null)
  }

  override def translateToPlan(
      tableEnv: StreamTableEnvironment,
      expectedType: Option[TypeInformation[Any]]): DataStream[Any] = {

    val config = tableEnv.getConfig

    val inputDataStream = getInput.asInstanceOf[DataStreamRel].translateToPlan(tableEnv)

    val returnType = determineReturnType(
      getRowType,
      expectedType,
      config.getNullCheck,
      config.getEfficientTypeUsage)

    val generator = new CodeGenerator(config, false, inputDataStream.getType)

    val body = functionBody(
      generator,
      inputDataStream.getType,
      getRowType,
      calcProgram,
      config,
      expectedType)

    val genFunction = generator.generateFunction(
      ruleDescription,
      classOf[FlatMapFunction[Any, Any]],
      body,
      returnType)

    val mapFunc = calcMapFunction(genFunction)
    inputDataStream.flatMap(mapFunc).name(calcOpName(calcProgram, getExpressionString))
  }
}
