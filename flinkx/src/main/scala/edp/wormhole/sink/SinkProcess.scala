/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.sink

import com.alibaba.fastjson.{JSON, JSONObject}
import edp.wormhole.common.util.JsonUtils
import edp.wormhole.common.KVConfig
import edp.wormhole.config.SinkProcessConfig
import edp.wormhole.swifts.SwiftsConfMemoryStorage
import edp.wormhole.ums.UmsFieldType.UmsFieldType
import edp.wormhole.ums.WormholeUms.toJsonCompact
import edp.wormhole.ums._
import edp.wormhole.util.{FlinkSchemaUtils, UmsFlowStartUtils}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.streaming.api.datastream.DataStreamSink
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.types.Row
import org.apache.log4j.Logger
import edp.wormhole.swifts.SwiftsConstants.PROTOCOL_TYPE

import scala.collection.mutable.ListBuffer

object SinkProcess extends Serializable {
  private lazy val logger = Logger.getLogger(this.getClass)

  def doProcess(dataStream: DataStream[Row], umsFlowStart: Ums, schemaMap: Map[String, (TypeInformation[_], Int)]): DataStreamSink[String] = {
    val umsFlowStartSchemas: Seq[UmsField] = umsFlowStart.schema.fields_get
    val umsFlowStartPayload: UmsTuple = umsFlowStart.payload_get.head
    val sinksStr = UmsFlowStartUtils.extractSinks(umsFlowStartSchemas, umsFlowStartPayload)
    val sinks = JSON.parseObject(sinksStr)
    val schemaMapWithUmsType: Map[String, (Int, UmsFieldType, Boolean)] = schemaMap.map(entry => (entry._1, (entry._2._2, FlinkSchemaUtils.FlinkType2UmsType(entry._2._1), true)))
    val sinkNamespace = UmsFlowStartUtils.extractSinkNamespace(umsFlowStartSchemas, umsFlowStartPayload)
    registerConnection(sinks, sinkNamespace)

    val rowSize = schemaMapWithUmsType.size
    val schemaList: Seq[(String, (Int, UmsFieldType, Boolean))] = schemaMapWithUmsType.toSeq.sortBy(_._2._1)
    val seqUmsField: Seq[UmsField] = schemaList.map(kv => UmsField(kv._1, kv._2._2, Some(kv._2._3))).drop(1)
    val schema = UmsSchema(sinkNamespace, Some(seqUmsField))

    val umsDataStream: DataStream[String] = dataStream.map {
      row =>
        val listBuffer = ListBuffer.empty[String]
        for (index <- 1 until rowSize) {
          val fieldInRow = row.getField(index)
          logger.info(s"field in row in umsDataStream map $fieldInRow")
          if (null == fieldInRow) listBuffer.append(null.asInstanceOf[String])
          else listBuffer.append(fieldInRow.toString)
        }
        val umsTuple = UmsTuple(listBuffer)
        val protocol = UmsProtocol(UmsProtocolType.umsProtocolType(row.getField(schemaMap(PROTOCOL_TYPE)._2).toString))
        val umsJson = toJsonCompact(Ums(protocol, schema, payload = Some(Seq(umsTuple))))
        logger.info("in SinkProcess.doProcess " + umsJson)
        umsJson
    }
    new Data2KafkaSink().process(umsDataStream, sinkNamespace, getSinkProcessConfig(sinks))
  }

  private def registerConnection(sinks: JSONObject, sinkNamespace: String): Unit = {
    val sink_connection_url = sinks.getString("sink_connection_url").trim.toLowerCase
    val sink_connection_username = if (sinks.containsKey("sink_connection_username")) Some(sinks.getString("sink_connection_username").trim) else None
    val sink_connection_password = if (sinks.containsKey("sink_connection_password")) Some(sinks.getString("sink_connection_password").trim) else None
    val parameters = if (sinks.containsKey("sink_connection_config") && sinks.getString("sink_connection_config").trim.nonEmpty) Some(JsonUtils.json2caseClass[Seq[KVConfig]](sinks.getString("sink_connection_config"))) else None
    SwiftsConfMemoryStorage.registerDataStoreConnectionsMap(sinkNamespace, sink_connection_url, sink_connection_username, sink_connection_password, parameters)
  }

  def getSinkProcessConfig(sinks: JSONObject): SinkProcessConfig = {
    val sink_table_keys = if (sinks.containsKey("sink_table_keys") && sinks.getString("sink_table_keys").trim.nonEmpty) Some(sinks.getString("sink_table_keys").trim.toLowerCase) else None
    val sink_specific_config = if (sinks.containsKey("sink_specific_config") && sinks.getString("sink_specific_config").trim.nonEmpty) Some(sinks.getString("sink_specific_config")) else None
    val sink_process_class_fullname = sinks.getString("sink_process_class_fullname").trim
    val sink_retry_times = sinks.getString("sink_retry_times").trim.toLowerCase.toInt
    val sink_retry_seconds = sinks.getString("sink_retry_seconds").trim.toLowerCase.toInt
    val sink_output = if (sinks.containsKey("sink_output") && sinks.getString("sink_output").trim.nonEmpty) {
      var tmpOutput = sinks.getString("sink_output").trim.toLowerCase.split(",").map(_.trim).mkString(",")
      if (tmpOutput.nonEmpty) {
        if (tmpOutput.indexOf(UmsSysField.TS.toString) < 0) {
          tmpOutput = tmpOutput + "," + UmsSysField.TS.toString
        }
        if (tmpOutput.indexOf(UmsSysField.ID.toString) < 0) {
          tmpOutput = tmpOutput + "," + UmsSysField.ID.toString
        }
        if (tmpOutput.indexOf(UmsSysField.OP.toString) < 0) {
          tmpOutput = tmpOutput + "," + UmsSysField.OP.toString
        }
      }
      tmpOutput
    } else ""
    val sink_schema = if (sinks.containsKey("sink_schema") && sinks.getString("sink_schema").trim.nonEmpty) {
      val sinkSchemaEncoded = sinks.getString("sink_schema").trim
      Some(new String(new sun.misc.BASE64Decoder().decodeBuffer(sinkSchemaEncoded.toString)))
    } else None

    SinkProcessConfig(sink_output, sink_table_keys, sink_specific_config, sink_schema, sink_process_class_fullname, sink_retry_times, sink_retry_seconds)
  }

}
