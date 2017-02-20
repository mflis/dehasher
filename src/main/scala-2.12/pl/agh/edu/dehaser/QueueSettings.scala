package pl.agh.edu.dehaser

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

/**
  * Created by razakroner on 2017-02-18.
  */

object QueueSettings {
  val HOST = "192.168.0.192"
  val PORT = 9000
  
  implicit val system = ActorSystem("Rest")
  implicit val materializer = ActorMaterializer()
  implicit val ctx = system.dispatcher

  val queue = system.actorOf(TaskQueue.props, "queue")
  val reporter = system.actorOf(Props[Reporter], "reporter")
}
