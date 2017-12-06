import com.google.protobuf.MessageLite
import java.util.*

class KotlinBar {
  fun getDefaultInstances() : List<MessageLite> {
    val list: MutableList<MessageLite> = ArrayList()
    list.add(io.grpc.helloworldexample.HelloWorld.HelloRequest.getDefaultInstance())
    list.add(io.grpc.helloworldexample.HelloWorld.HelloReply.getDefaultInstance())
    return list
  }
}
