package com.zhisheng.common.utils;


import com.zhisheng.common.model.Metrics;
import com.zhisheng.common.schemas.MetricSchema;
import com.zhisheng.common.watermarks.MetricWatermark;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer011;
import org.apache.flink.streaming.connectors.kafka.internals.KafkaTopicPartition;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class KafkaConfigUtil {
    /**
     * 设置 kafka 配置
     *
     * @param parameterTool
     * @return
     */
    public static Properties buildKafkaProps(ParameterTool parameterTool) {
        Properties props = parameterTool.getProperties();
        props.put("bootstrap.servers", parameterTool.get("kafka.brokers"));
        props.put("zookeeper.connect", parameterTool.get("kafka.zookeeper.connect"));
        props.put("group.id", parameterTool.get("kafka.group.id"));
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", "latest");
        return props;
    }


    public static DataStreamSource<Metrics> buildSource(StreamExecutionEnvironment env) throws IllegalAccessException {
        ParameterTool parameter = (ParameterTool) env.getConfig().getGlobalJobParameters();
        String topic = parameter.getRequired("metrics.topic");
        Long time = parameter.getLong("consumer.from.time", 0L);
        return buildSource(env, topic, time);
    }

    /**
     * @param env
     * @param topic
     * @param time  订阅的时间
     * @return
     * @throws IllegalAccessException
     */
    public static DataStreamSource<Metrics> buildSource(StreamExecutionEnvironment env, String topic, Long time) throws IllegalAccessException {
        ParameterTool parameterTool = (ParameterTool) env.getConfig().getGlobalJobParameters();
        Properties props = buildKafkaProps(parameterTool);
        FlinkKafkaConsumer011<Metrics> consumer = new FlinkKafkaConsumer011<>(
                topic,
                new MetricSchema(),
                props);
        //重置offset到time时刻
        if (time != 0L) {
            Map<KafkaTopicPartition, Long> partitionOffset = buildOffsetByTime(props, parameterTool, time);
            consumer.setStartFromSpecificOffsets(partitionOffset);
        }
        return env.addSource(consumer);
    }

    private static Map<KafkaTopicPartition, Long> buildOffsetByTime(Properties props, ParameterTool parameterTool, Long time) {
        props.setProperty("group.id", "query_time_" + time);
        KafkaConsumer consumer = new KafkaConsumer(props);
        List<PartitionInfo> partitionsFor = consumer.partitionsFor(parameterTool.getRequired("metrics.topic"));
        Map<TopicPartition, Long> partitionInfoLongMap = new HashMap<>();
        for (PartitionInfo partitionInfo : partitionsFor) {
            partitionInfoLongMap.put(new TopicPartition(partitionInfo.topic(), partitionInfo.partition()), time);
        }
        Map<TopicPartition, OffsetAndTimestamp> offsetResult = consumer.offsetsForTimes(partitionInfoLongMap);
        Map<KafkaTopicPartition, Long> partitionOffset = new HashMap<>();
        offsetResult.entrySet().forEach(entry -> partitionOffset.put(new KafkaTopicPartition(entry.getKey().topic(), entry.getKey().partition()), entry.getValue().offset()));

        consumer.close();
        return partitionOffset;
    }

    public static SingleOutputStreamOperator<Metrics> parseSource(DataStreamSource<Metrics> dataStreamSource) {
        return dataStreamSource.assignTimestampsAndWatermarks(new MetricWatermark());
    }
}