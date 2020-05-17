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

package org.myorg.quickstart


import java.util.Properties

import org.apache.flink.api.common.functions.RuntimeContext
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows


import org.apache.flink.shaded.zookeeper.org.apache.zookeeper.jute.compiler.JString
import org.apache.flink.shaded.zookeeper.org.apache.zookeeper.jute.compiler.JInt
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows
import org.apache.flink.streaming.connectors.elasticsearch.{ElasticsearchSinkFunction, RequestIndexer}
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink
import org.apache.http.HttpHost
import org.elasticsearch.action.index.IndexRequest
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.api.common.functions.RuntimeContext
import org.elasticsearch.client.Requests


object StreamingJob {

  var cond = 0.1
  def main(args: Array[String]) {
    // set up the streaming execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.enableCheckpointing(5000) // checkpoint every 5000 msecs


    val properties = new Properties()
    properties.setProperty("bootstrap.servers", "localhost:9092")
    properties.setProperty("group.id", "samplegroup")

    //Link flink to Kafka topics

    val displayStream = env.addSource(new FlinkKafkaConsumer[String]("displays", new SimpleStringSchema(), properties))
    //.writeAsText("/home/azzo/Desktop/displayStream.txt")
    val clickStream = env.addSource(new FlinkKafkaConsumer[String]("clicks", new SimpleStringSchema(), properties))
    //.writeAsText("/home/azzo/Desktop/clickStream.txt")


    // Let's extract the uid from our dataStreams
    def uid_Extraction(l: Array[String]): (String, Int, String) = {
      val uid = l(1).split(":")(1)
      val timestamp = l(2).split(":")(1).toInt
      val ip = l(3).split(":")(1)
      return (uid, timestamp, ip)
    }

    // Lets find Users whom use the same ip.
    val click_ip = clickStream.map(_.split(","))
      .map(x => (uid_Extraction(x), 1.0))
      .keyBy { elem =>
        elem match {
          case ((_, _, ip), _) => ip
        }
      }
      .timeWindow(Time.seconds(10))
      .sum(1)


    val impression_ip = displayStream.map(_.split(","))
      .map(x => (uid_Extraction(x), 1.0))
      .keyBy { elem =>
        elem match {
          case ((_, _, ip), _) => ip
        }
      }
      .timeWindow(Time.seconds(10))
      .sum(1)


    // Let's find out users who change their ips and keep their same uid.
    val impression_uid = displayStream.map(_.split(","))
      .map(x => (uid_Extraction(x), 1.0)).assignAscendingTimestamps {
      case ((_, timestamp, _), _) => timestamp
    }
      .keyBy { elem =>
        elem match {
          case ((uid, _, _), _) => uid
        }
      }
      .timeWindow(Time.seconds(10))
      .sum(1)

    val click_uid = clickStream.map(_.split(","))
      .map(x => (uid_Extraction(x), 1.0)).assignAscendingTimestamps {
      case ((_, timestamp, _), _) => timestamp
    }
      .keyBy { elem =>
        elem match {
          case ((uid, _, _), _) => uid
        }
      }
      .timeWindow(Time.seconds(10))
      .sum(1)


    //Anomaly Extraction
    var ip_anomaly = impression_ip.join(click_ip).where(x => x._1._3).equalTo(x => x._1._3)
      .window(TumblingProcessingTimeWindows.of(Time.seconds(5)))
      .apply((a, b) => (b._1._3, b._2 / a._2))
      .filter(x => x._2 > cond).print()
    //.writeAsText("/home/azzo/Desktop/ip_anomaly.json")

    var uid_anomaly = impression_uid.join(click_uid).where(x => x._1._1).equalTo(x => x._1._1)
      .window(TumblingProcessingTimeWindows.of(Time.seconds(5)))
      .apply((a, b) => (b._1._1, b._2 / a._2))
      .filter(x => x._2 > cond).print()
    //.writeAsText("/home/azzo/Desktop/uid_anomaly.json")

    //Polishing clicks and displays streams

    def polishing(l: Array[String]): (String,String,Int,String,String) = {
      val event=l(0).split(":")(1)
      val uid = l(1).split(":")(1)
      val timestamp = l(2).split(":")(1).toInt
      val ip = l(3).split(":")(1)
      val impression=l(0).split(":")(1)
      return (event,uid,timestamp,ip,impression)
    }
    val polished_click=clickStream.map(_.split(",")).map(x=>polishing(x))
    //.writeAsText("/home/azzo/Desktop/Polished_clickStream.txt")
    val polished_display=displayStream.map(_.split(",")).map(x=>polishing(x))
    //.writeAsText("/home/azzo/Desktop/Polished_displayStream.txt")

    val httpHosts = new java.util.ArrayList[HttpHost]
    httpHosts.add(new HttpHost("127.0.0.1", 9200, "http"))
    httpHosts.add(new HttpHost("10.2.3.1", 9200, "http"))
    val esSinkBuilder = new ElasticsearchSink.Builder[(String,String,Int,String,String)](
      httpHosts,
      new ElasticsearchSinkFunction[(String,String,Int,String,String)] {
        def process(x: (String,String,Int,String,String), ctx: RuntimeContext, indexer: RequestIndexer) {
          val json = new java.util.HashMap[String,Any]
          json.put("event", x._1)
          json.put("uid", x._2)
          json.put("timestamp", x._3)
          json.put("ip", x._4)
          json.put("impression", x._5)
          val rqst: IndexRequest = Requests.indexRequest
            .index("clicks")
            .source(json)
          indexer.add(rqst)
        }
      }
    )

    // configuration for the bulk requests; this instructs the sink to emit after every element, otherwise they would be buffered
    esSinkBuilder.setBulkFlushMaxActions(1)

    // finally, build and add the sink to the job's pipeline
    polished_click.addSink(esSinkBuilder.build)
    polished_display.addSink(esSinkBuilder.build)




    // Let's execute our program
    env.execute("Flink Click Anomaly Detection")
  }
}
