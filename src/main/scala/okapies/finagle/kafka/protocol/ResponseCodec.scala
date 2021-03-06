package okapies.finagle.kafka.protocol

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.{Channel, ChannelHandlerContext}
import org.jboss.netty.handler.codec.oneone.{OneToOneEncoder, OneToOneDecoder}

class BatchResponseDecoder(correlator: RequestCorrelator) extends OneToOneDecoder {

  import ResponseDecoder._
  import Spec._

  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case frame: ChannelBuffer =>
      // Note: MessageSize field must be discarded by previous frame decoder.
      val correlationId = frame.decodeInt32()
      correlator(correlationId).flatMap { apiKey =>
        Option(decodeResponse(apiKey, correlationId, frame))
      }.getOrElse(msg)
    case _ => msg // fall through
  }

}

class StreamResponseDecoder extends OneToOneDecoder {

  import com.twitter.concurrent.{Broker => TwitterBroker}
  import com.twitter.util.Promise

  import ResponseDecoder._
  import Spec._

  private[this] var partitions: TwitterBroker[PartitionStatus] = null

  private[this] var messages: TwitterBroker[FetchedMessage] = null

  private[this] var complete: Promise[Unit] = null

  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case BufferResponseFrame(apiKey, correlationId, frame) =>
      decodeResponse(apiKey, correlationId, frame)

    case FetchResponseFrame(correlationId) =>
      partitions = new TwitterBroker[PartitionStatus]
      messages = new TwitterBroker[FetchedMessage]
      complete = new Promise[Unit]

      StreamFetchResponse(correlationId, partitions.recv, messages.recv, complete)
    case partition: PartitionStatus =>
      partitions ! partition

      null
    case msg: FetchedMessage =>
      messages ! msg

      null
    case NilMessageFrame =>
      complete.setValue(())

      partitions = null
      messages = null
      complete = null

      null
    case _ => msg // fall through
  }

}

object ResponseDecoder {

  import Spec._

  def decodeResponse(apiKey: Short, correlationId: Int, frame: ChannelBuffer) = apiKey match {
    case ApiKeyProduce => decodeProduceResponse(correlationId, frame)
    case ApiKeyFetch => decodeFetchResponse(correlationId, frame)
    case ApiKeyOffset => decodeOffsetResponse(correlationId, frame)
    case ApiKeyMetadata => decodeMetadataResponse(correlationId, frame)
    case ApiKeyLeaderAndIsr => null
    case ApiKeyStopReplica => null
    case ApiKeyOffsetCommit => decodeOffsetCommitResponse(correlationId, frame)
    case ApiKeyOffsetFetch => decodeOffsetFetchResponse(correlationId, frame)
  }

  /**
   * {{{
   * ProduceResponse => [TopicName [Partition ErrorCode Offset]]
   * }}}
   */
  private[this] def decodeProduceResponse(correlationId: Int, buf: ChannelBuffer) = {
    val results = buf.decodeArray {
      val topicName = buf.decodeString()

      // [Partition ErrorCode Offset]
      val partitions = buf.decodeArray {
        val partition = buf.decodeInt32()
        val error: KafkaError = buf.decodeInt16()
        val offset = buf.decodeInt64()

        partition -> ProduceResult(error, offset)
      }.toMap

      topicName -> partitions
    }.toMap

    ProduceResponse(correlationId, results)
  }

  /**
   * {{{
   * FetchResponse => [TopicName [Partition ErrorCode HighwaterMarkOffset MessageSetSize MessageSet]]
   * }}}
   */
  private[this] def decodeFetchResponse(correlationId: Int, buf: ChannelBuffer) = {
    val results = buf.decodeArray {
      val topicName = buf.decodeString()

      // [Partition ErrorCode HighwaterMarkOffset MessageSetSize MessageSet]
      val partitions = buf.decodeArray {
        val partition = buf.decodeInt32()
        val error: KafkaError = buf.decodeInt16()
        val highwaterMarkOffset = buf.decodeInt64()
        val messages = buf.decodeMessageSet()

        partition -> FetchResult(error, highwaterMarkOffset, messages)
      }.toMap

      topicName -> partitions
    }.toMap

    FetchResponse(correlationId, results)
  }

  /**
   * {{{
   * OffsetResponse => [TopicName [PartitionOffsets]]
   *   PartitionOffsets => Partition ErrorCode [Offset]
   * }}}
   */
  private[this] def decodeOffsetResponse(correlationId: Int, buf: ChannelBuffer) = {
    val results = buf.decodeArray {
      val topicName = buf.decodeString()

      // [Partition ErrorCode [Offset]]
      val partitions = buf.decodeArray {
        val partition = buf.decodeInt32()
        val error: KafkaError = buf.decodeInt16()
        val offsets = buf.decodeArray(buf.decodeInt64())

        partition -> OffsetResult(error, offsets)
      }.toMap

      topicName -> partitions
    }.toMap

    OffsetResponse(correlationId, results)
  }

  /**
   * {{{
   * MetadataResponse => [Broker][TopicMetadata]
   *   Broker => NodeId Host Port
   *   TopicMetadata => TopicErrorCode TopicName [PartitionMetadata]
   *     PartitionMetadata => PartitionErrorCode PartitionId Leader Replicas Isr
   * }}}
   */
  private[this] def decodeMetadataResponse(correlationId: Int, buf: ChannelBuffer) = {
    // [Broker]
    val brokers = buf.decodeArray {
      val nodeId = buf.decodeInt32()
      val host = buf.decodeString()
      val port = buf.decodeInt32()

      Broker(nodeId, host, port)
    }
    val toBroker = brokers.map(b => (b.nodeId, b)).toMap // nodeId to Broker

    // [TopicMetadata]
    val topics = buf.decodeArray {
      val errorCode = buf.decodeInt16()
      val name = buf.decodeString()

      // [PartitionMetadata]
      val partitions = buf.decodeArray {
        val errorCode = buf.decodeInt16()
        val id = buf.decodeInt32()
        val leader = toBroker.get(buf.decodeInt32())
        val replicas = buf.decodeArray(toBroker(buf.decodeInt32()))
        val isr = buf.decodeArray(toBroker(buf.decodeInt32()))

        PartitionMetadata(errorCode, id, leader, replicas, isr)
      }

      TopicMetadata(errorCode, name, partitions)
    }

    MetadataResponse(correlationId, brokers, topics)
  }

  /**
   * Not implemented in Kafka 0.8. See KAFKA-993
   * Implemented in Kafka 0.8.1
   *
   * {{{
   * OffsetCommitResponse => [TopicName [Partition ErrorCode]]]
   * }}}
   */
  private[this] def decodeOffsetCommitResponse(correlationId: Int, buf: ChannelBuffer) = {
    val results = buf.decodeArray {
      val topicName = buf.decodeString()

      // [Partition ErrorCode [Offset]]
      val partitions = buf.decodeArray {
        val partition = buf.decodeInt32()
        val error: KafkaError = buf.decodeInt16()

        partition -> OffsetCommitResult(error)
      }.toMap

      topicName -> partitions
    }.toMap

    OffsetCommitResponse(correlationId, results)
  }

  /**
   * Not implemented in Kafka 0.8. See KAFKA-993
   * Implemented in Kafka 0.8.1
   *
   * {{{
   * OffsetFetchResponse => [TopicName [Partition Offset Metadata ErrorCode]]
   * }}}
   */
  private[this] def decodeOffsetFetchResponse(correlationId: Int, buf: ChannelBuffer) = {
    val results = buf.decodeArray {
      val topicName = buf.decodeString()

      // [Partition ErrorCode [Offset]]
      val partitions = buf.decodeArray {
        val partition = buf.decodeInt32()
        val offset = buf.decodeInt64()
        val metadata = buf.decodeString()
        val error: KafkaError = buf.decodeInt16()

        partition -> OffsetFetchResult(offset, metadata, error)
      }.toMap

      topicName -> partitions
    }.toMap

    OffsetFetchResponse(correlationId, results)
  }

}

/**
 *  TODO: Not implemented yet.
 */
class ResponseEncoder extends OneToOneEncoder {

  import Spec._

  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg

}
