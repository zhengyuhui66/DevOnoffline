package com.hiklife.devonoffline

import org.apache.commons.configuration.XMLConfiguration

class ConfigUtil(path: String) extends Serializable {
  val conf = new XMLConfiguration(path)

  def getConfigSetting(key: String, default: String): String ={
    if(conf != null)
      conf.getString(key)
    else
      default
  }

  /*
  spark应用名称
   */
  val appName: String = getConfigSetting("appName", "DevOnoffline")

  /*
  每隔多少时间从kafka获取数据，单位秒
   */
  val duration: Long = getConfigSetting("duration", "10").toLong

  /*
  kafka集群地址
   */
  val brokers: String = getConfigSetting("brokers", "")

  /*
  kafka消费团体
   */
  val group: String = getConfigSetting("group", "DevOnofflineGroup")

  /*
  kafka订阅主题
   */
  val topic: String = getConfigSetting("topic", "DevOnofflineTopic")

  /*
  kafka消费偏移量方式
   */
  val offset: String = getConfigSetting("offset", "latest")

  /*
  设备上下线日志表
   */
  val devOnofflineTable: String = getConfigSetting("devOnofflineTable", "")

  /*
  redis host
   */
  val redisHost: String = getConfigSetting("redisHost", "")

  /*
  redis port
   */
  val redisPort: Int = getConfigSetting("redisPort", "").toInt

  /*
  redis timeout
   */
  val redisTimeout: Int = getConfigSetting("redisTimeout", "").toInt

  /*
  kafka redis key
   */
  val kafkaOffsetKey: String = getConfigSetting("kafkaOffsetKey", "")

}
