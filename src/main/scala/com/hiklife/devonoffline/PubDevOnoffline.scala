package com.hiklife.devonoffline

import java.text.SimpleDateFormat
import java.util.Date

class PubDevOnoffline(records: Array[String]) extends Serializable {
  val sDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  /**
    * 设备编号
    */
  var devID: String = records(0)

  /**
    * 上下线时间
    */
  var tm: String = records(1)
  var dtm: Date = sDateFormat.parse(tm)

  /**
    * 上下线标志
    */
  var st: String = records(2).substring(0,1)

  def toJSONString: String ={
    "{\"devID\":\"" + devID + "\"," +
      "\"st\":" + st + "," +
      "\"tm\":\"" + tm + "\"," +
      "}"
  }
}
