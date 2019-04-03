package com.hiklife.devonoffline

import java.util.Date
import java.util.concurrent.{Callable, Executors}

import com.hiklife.utils._
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.{ConnectionFactory, HTable, Put}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, KafkaUtils, OffsetRange}
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.{SparkConf, SparkContext, TaskContext}
import org.apache.spark.streaming.{Seconds, StreamingContext}

object SparkApp {
  def main(args: Array[String]): Unit = {
    val path = args(0)
    val configUtil = new ConfigUtil(path + "dataAnalysis/devOnoffline.xml")
    var hBaseUtil = new HBaseUtil(path + "hbase/hbase-site.xml")

    val conf = new SparkConf().setAppName(configUtil.appName)
    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Seconds(configUtil.duration))

    val executors = Executors.newCachedThreadPool()
    executors.submit(new Callable[Unit] {
      override def call(): Unit ={
        //创建表
        val tableName = configUtil.devOnofflineTable
        hBaseUtil.createTable(tableName, "RD")
        //广播变量传递参数，减少副本数
        val broadList = sc.broadcast(List(path + "hbase/hbase-site.xml",tableName,configUtil.redisHost,configUtil.redisPort,configUtil.redisTimeout,configUtil.kafkaOffsetKey))
        //从redis中获取kafka上次消费偏移量
        val redisUtil = new RedisUtil(configUtil.redisHost,configUtil.redisPort,configUtil.redisTimeout)
        val results = redisUtil.getObject(configUtil.kafkaOffsetKey)
        //创建sparkstreaming
        val topic=createTopic(configUtil)
        val kafkaParam = createKafkaParam(configUtil)
        val stream = if (results == null || results == None){
          //从最新开始消费
          KafkaUtils.createDirectStream[String,String](ssc, PreferConsistent,Subscribe[String,String](topic,kafkaParam))
        }else{
          //从上次消费偏移位置开始消费
          var fromOffsets: Map[TopicPartition, Long] = Map()
          val map = results.asInstanceOf[Map[String,String]]
          for (result <- map) {
            val nor = result._1.split("_")
            val tp = new TopicPartition(nor(0), nor(1).toInt)
            fromOffsets += (tp -> result._2.toLong)
          }
          KafkaUtils.createDirectStream[String,String](ssc, PreferConsistent,Subscribe[String,String](topic,kafkaParam,fromOffsets))
        }

        stream.foreachRDD(rdd =>{
          val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges
          rdd.foreachPartition(partitionOfRecords => {
            val conn = ConnectionFactory.createConnection(HBaseUtil.getConfiguration(broadList.value(0).toString))
            val table = conn.getTable(TableName.valueOf(broadList.value(1).toString)).asInstanceOf[HTable]
            table.setAutoFlush(false, false)
            table.setWriteBufferSize(5 * 1024 * 1024)

            partitionOfRecords.foreach(record => {
              val records = record.value().split("\t")
              if(records.length == 3) {
                var pubDevOnoffline = new PubDevOnoffline(records)
                val rowkey = getRowkey(pubDevOnoffline.devID, pubDevOnoffline.dtm)
                val put = new Put(rowkey.getBytes)
                put.addColumn("RD".getBytes, "V".getBytes, pubDevOnoffline.toJSONString.getBytes)
                table.put(put)

              }
            })
            table.flushCommits()
            table.close()
            conn.close()

            //记录本次消费offset
            val o: OffsetRange = offsetRanges(TaskContext.get.partitionId)
            val key = s"${o.topic}_${o.partition}"
            val redisUtil = new RedisUtil(broadList.value(2).toString, broadList.value(3).toString.toInt, broadList.value(4).toString.toInt)
            val kafkaOffsetKey = broadList.value(5).toString

            var isRun = false
            while (!isRun){
              isRun = setOffset(redisUtil,kafkaOffsetKey,key,o.fromOffset.toString)
            }

          })

        })

        ssc.start()
        ssc.awaitTermination()

      }
    })
  }

  /**
    * 创建kafka参数
    *
    * @param configUtil
    * @return
    */
  def createKafkaParam(configUtil: ConfigUtil): collection.Map[String, Object] ={
    val kafkaParam = Map(
      "bootstrap.servers" -> configUtil.brokers,//kafka集群地址
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> configUtil.group, //用于标识这个消费者属于哪个消费团体
      "auto.offset.reset" -> configUtil.offset,//latest自动重置偏移量为最新的偏移量
      "enable.auto.commit" -> (false: java.lang.Boolean)//如果是true，则这个消费者的偏移量会在后台自动提交
    )
    kafkaParam
  }

  /**
    * 创建kafka主题
    *
    * @param configUtil
    * @return
    */
  def createTopic(configUtil: ConfigUtil): Iterable[String] ={
    var topic=Array(configUtil.topic)
    topic
  }

  /**
    * 根据设备编号和时间信息获取对应的rowkey
    * hash(devid)+devid+datetime
    *
    * @param devid
    * @param datetime
    * @return
    */
  def getRowkey(devid: String, datetime: Date): String ={
    val prefix = new Array[Byte](2)
    ByteUtil.putShort(prefix, CommUtil.getHashCodeWithLimit(devid, 0xFFFE).asInstanceOf[Short], 0)
    val timestamp = new Array[Byte](4)
    ByteUtil.putInt(timestamp, (2524608000L - datetime.getTime / 1000).asInstanceOf[Int], 0)
    val keyrow = CommUtil.byte2HexStr(prefix) + CommUtil.formatContext(devid, 10) + CommUtil.byte2HexStr(timestamp)
    keyrow
  }

  def setOffset(redisUtil: RedisUtil, kafkaOffsetKey: String, fromOffsetKey: String, fromOffsetVal: String): Boolean ={
    val results = redisUtil.getObject(kafkaOffsetKey)
    if (results == null || results == None) {
      val map = Map(fromOffsetKey -> fromOffsetVal)
      redisUtil.watchSetObject(kafkaOffsetKey, map)
    } else {
      var map = results.asInstanceOf[Map[String, String]]
      map += (fromOffsetKey -> fromOffsetVal)
      redisUtil.watchSetObject(kafkaOffsetKey, map)
    }
  }

}
