package com.example.zhttpservice

import zio.test._
import zio.test.Assertion._
import zhttp.http._

object ZhttpServiceSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("""ZhttpServiceSpec""")(
    testM("200 ok") {
      checkAllM(Gen.fromIterable(List("text", "json"))) { uri =>
        val request = Request(Method.GET, URL(!! / uri))
        assertM(ZhttpService.app(request).map(_.status))(equalTo(Status.OK))
      }
    },
  )
}
