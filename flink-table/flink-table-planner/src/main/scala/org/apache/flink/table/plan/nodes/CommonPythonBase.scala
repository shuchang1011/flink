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
package org.apache.flink.table.plan.nodes

import org.apache.calcite.rex.{RexCall, RexLiteral, RexNode}
import org.apache.calcite.sql.`type`.SqlTypeName
import org.apache.flink.api.java.ExecutionEnvironment
import org.apache.flink.configuration.{ConfigOption, Configuration}
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.{TableConfig, TableException}
import org.apache.flink.table.functions.UserDefinedFunction
import org.apache.flink.table.functions.python.{PythonFunction, PythonFunctionInfo}
import org.apache.flink.table.functions.utils.{ScalarSqlFunction, TableSqlFunction}
import org.apache.flink.table.util.DummyStreamExecutionEnvironment

import scala.collection.mutable
import scala.collection.JavaConversions._

trait CommonPythonBase {
  protected def loadClass(className: String): Class[_] = {
    try {
      Class.forName(className, false, Thread.currentThread.getContextClassLoader)
    } catch {
      case ex: ClassNotFoundException => throw new TableException(
        "The dependency of 'flink-python' is not present on the classpath.", ex)
    }
  }

  private lazy val convertLiteralToPython = {
    val clazz = loadClass("org.apache.flink.api.common.python.PythonBridgeUtils")
    clazz.getMethod("convertLiteralToPython", classOf[RexLiteral], classOf[SqlTypeName])
  }

  private def createPythonFunctionInfo(
      pythonRexCall: RexCall,
      inputNodes: mutable.Map[RexNode, Integer],
      func: UserDefinedFunction): PythonFunctionInfo = {
    val inputs = new mutable.ArrayBuffer[AnyRef]()
    pythonRexCall.getOperands.foreach {
      case pythonRexCall: RexCall =>
        // Continuous Python UDFs can be chained together
        val argPythonInfo = createPythonFunctionInfo(pythonRexCall, inputNodes)
        inputs.append(argPythonInfo)

      case literal: RexLiteral =>
        inputs.append(
          convertLiteralToPython.invoke(null, literal, literal.getType.getSqlTypeName))

      case argNode: RexNode =>
        // For input arguments of RexInputRef, it's replaced with an offset into the input row
        inputNodes.get(argNode) match {
          case Some(existing) => inputs.append(existing)
          case None =>
            val inputOffset = Integer.valueOf(inputNodes.size)
            inputs.append(inputOffset)
            inputNodes.put(argNode, inputOffset)
        }
    }

    new PythonFunctionInfo(func.asInstanceOf[PythonFunction], inputs.toArray)
  }

  protected def createPythonFunctionInfo(
      pythonRexCall: RexCall,
      inputNodes: mutable.Map[RexNode, Integer]): PythonFunctionInfo = {
    pythonRexCall.getOperator match {
      case sfc: ScalarSqlFunction =>
        createPythonFunctionInfo(pythonRexCall, inputNodes, sfc.getScalarFunction)
      case tfc: TableSqlFunction =>
        createPythonFunctionInfo(pythonRexCall, inputNodes, tfc.getTableFunction)
    }
  }

  protected def getMergedConfig(
      env: ExecutionEnvironment,
      tableConfig: TableConfig): Configuration = {
    val clazz = loadClass(CommonPythonBase.PythonConfigUtil)
    val method = clazz.getDeclaredMethod(
      "getMergedConfig", classOf[ExecutionEnvironment], classOf[TableConfig])
    val config = method.invoke(null, env, tableConfig).asInstanceOf[Configuration]
    config
  }

  protected def getMergedConfig(
      env: StreamExecutionEnvironment,
      tableConfig: TableConfig): Configuration = {
    val clazz = loadClass(CommonPythonBase.PythonConfigUtil)
    val realEnv = getRealEnvironment(env)
    val method = clazz.getDeclaredMethod(
      "getMergedConfig", classOf[StreamExecutionEnvironment], classOf[TableConfig])
    val config = method.invoke(null, realEnv, tableConfig).asInstanceOf[Configuration]
    config
  }

  private def getRealEnvironment(env: StreamExecutionEnvironment): StreamExecutionEnvironment = {
    val realExecEnvField = classOf[DummyStreamExecutionEnvironment].getDeclaredField("realExecEnv")
    realExecEnvField.setAccessible(true)
    var realEnv = env
    while (realEnv.isInstanceOf[DummyStreamExecutionEnvironment]) {
      realEnv = realExecEnvField.get(realEnv).asInstanceOf[StreamExecutionEnvironment]
    }
    realEnv
  }

  protected def isPythonWorkerUsingManagedMemory(config: Configuration): Boolean = {
    val clazz = loadClass("org.apache.flink.python.PythonOptions")
    config.getBoolean(clazz.getField("USE_MANAGED_MEMORY").get(null)
      .asInstanceOf[ConfigOption[java.lang.Boolean]])
  }
}

object CommonPythonBase {
  val PythonConfigUtil = "org.apache.flink.python.util.PythonConfigUtil"
}
