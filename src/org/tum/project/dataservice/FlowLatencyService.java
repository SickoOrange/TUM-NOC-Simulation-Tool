package org.tum.project.dataservice;

import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import org.tum.project.bean.FlowAnalysisResult;
import org.tum.project.bean.PacketTimingInfo;
import org.tum.project.callback.DataUpdateCallback;
import org.tum.project.constant.ConstantValue;
import org.tum.project.utils.sqlUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlowLatencyService {

    private static HashMap<Integer, PacketTimingInfo> packet_id_to_info_map = new HashMap<Integer, PacketTimingInfo>();
    private static HashMap<Integer, FlowAnalysisResult> flow_id_to_analysis_map = new HashMap<Integer, FlowAnalysisResult>();
    private static HashMap<Long, String> flowChartDataMap = new HashMap<Long, String>();
    private static StringBuffer analyzeResult = new StringBuffer("");

    private static HashMap<Integer, List<PacketTimingInfo>> flow_id_to_packetInfo_map = new HashMap<>();

    private DataUpdateCallback callback;

    public void setCallback(DataUpdateCallback callback) {
        if (callback != null) {
            this.callback = callback;
        }
    }

    private StringBuffer recoverInformation = new StringBuffer();






    /**
     * start the analysis
     *
     * @param dataModelName   module table name list
     * @param currentDataBase current data base
     */
    public void startAnalyze(List<String> dataModelName, String currentDataBase) {
        execute(dataModelName, currentDataBase);
        callback.updateFLowLatency(flowChartDataMap,analyzeResult);
    }


    /**
     * execute the flow latency analysis
     *
     * @param dataModelName   module table name in the dabase
     * @param currentDataBase current database name
     */
    private void execute(List<String> dataModelName, String currentDataBase) {
        initVariables();

        //parse  PacketTimingInfo
        parseProducer(dataModelName, currentDataBase);

        //parse   Consumer
        parseConsumer(dataModelName, currentDataBase);

        //analyze all flows
        analyzeAllFlows();

        //report all flows
        reportAllFlows();

    }



    private void reportAllFlows() {
        int flow_number = 0;
        double total_noc_latency = 0;
        int total_noc_packet_number = 0;
        int total_noc_success_packet_number = 0;

        for (Map.Entry<Integer, FlowAnalysisResult> entry : flow_id_to_analysis_map.entrySet()) {
            long flow_id = entry.getKey();
            FlowAnalysisResult flowAnalysis = entry.getValue();
            double total_flow_latency = 0;
            for (Map.Entry<Double, Integer> set : flowAnalysis.getLatency_counter().entrySet()) {
                double latency = set.getKey();
                int number = set.getValue();
                total_flow_latency = latency * number + total_flow_latency;
            }
            float average_flow_latency = (float) (total_flow_latency / (flowAnalysis.getSuccessfulPacketNumber()));
            System.out.println("Flow" + flow_id + ": average_latency=" + average_flow_latency
                    + " total_pkt=" + flowAnalysis.getGeneratedPacketNumber() + " success_pkt=" + flowAnalysis.getSuccessfulPacketNumber());

            analyzeResult.append("Flow" + flow_id + ": average_latency=" + average_flow_latency
                    + " ns, total_pkt=" + flowAnalysis.getGeneratedPacketNumber() + ", success_pkt=" + flowAnalysis.getSuccessfulPacketNumber() + "\n");
            String s = average_flow_latency + "_" + flowAnalysis.getGeneratedPacketNumber() + "_" + flowAnalysis.getSuccessfulPacketNumber();
            flowChartDataMap.put(flow_id, s);


            flow_number++;
            total_noc_latency = total_noc_latency + total_flow_latency;
            total_noc_packet_number = total_noc_packet_number + flowAnalysis.getGeneratedPacketNumber();
            total_noc_success_packet_number = total_noc_success_packet_number + flowAnalysis.getSuccessfulPacketNumber();
        }
        analyzeResult.append("\n");
        System.out.println("SUMMARY: flow number " + flow_number + " total_pkt " + total_noc_packet_number
                + " success_pkt " + total_noc_success_packet_number + " average_latency " + (float) total_noc_latency / total_noc_success_packet_number);
        analyzeResult.append("SUMMARY: flow number " + flow_number + ", total_pkt " + total_noc_packet_number
                + ", success_pkt " + total_noc_success_packet_number + ", average_latency " + (float) total_noc_latency / total_noc_success_packet_number + " ns" + "\n");


        recoverInformation.append(analyzeResult.toString());

    }


    private void analyzeAllFlows() {

        for (Map.Entry<Integer, PacketTimingInfo> entry : packet_id_to_info_map.entrySet()) {

            FlowAnalysisResult flowAnalysis;
            List<PacketTimingInfo> successfulPacketList;
            if (flow_id_to_analysis_map.containsKey(entry.getValue().getFlow_id())) {
                flowAnalysis = flow_id_to_analysis_map.get(entry.getValue().getFlow_id());
                successfulPacketList = flow_id_to_packetInfo_map.get(entry.getValue().getFlow_id());
            } else {
                flowAnalysis = new FlowAnalysisResult();
                flow_id_to_analysis_map.put(entry.getValue().getFlow_id(), flowAnalysis);
                successfulPacketList = new ArrayList<>();
                flow_id_to_packetInfo_map.put(entry.getValue().getFlow_id(), successfulPacketList);
            }
            Integer generatedPacketNumber = flowAnalysis.getGeneratedPacketNumber();
            generatedPacketNumber++;
            flowAnalysis.setGeneratedPacketNumber(generatedPacketNumber);

            if (entry.getValue().getConsume_time() <= entry.getValue().getProduce_time()) {
                System.out.println("missing flit id= " + entry.getValue().getPacket_id() + " flow_id= " + entry.getValue()
                        .getFlow_id() + " produce_time= " + entry.getValue().getProduce_time());
                analyzeResult.append("missing flit id= " + entry.getValue().getPacket_id() + ", flow_id= " + entry.getValue()
                        .getFlow_id() + ", produce_time= " + entry.getValue().getProduce_time() + " ns" + "\n");
            } else {
                Integer successfulPacketNumber = flowAnalysis.getSuccessfulPacketNumber();
                successfulPacketNumber++;
                flowAnalysis.setSuccessfulPacketNumber(successfulPacketNumber);
                double latency = entry.getValue().getConsume_time() - entry.getValue().getProduce_time();
                if (flowAnalysis.getLatency_counter().containsKey(latency)) {
                    int n = flowAnalysis.getLatency_counter().get(latency);
                    n++;
                    flowAnalysis.getLatency_counter().put(latency, n);
                } else {
                    flowAnalysis.getLatency_counter().put(latency, 1);
                }

                //record information for successful packet
                successfulPacketList.add(entry.getValue());
            }
        }
        System.out.println(flow_id_to_packetInfo_map);
        analyzeResult.append("\n");
    }

    private void parseConsumer(List<String> table, String database) {
        Connection conn = null;
        try {
            conn = sqlUtils.getMySqlConnection(database);
            Statement statement = conn.createStatement();
            ResultSet rs = null;
            for (String name : table) {
                String queryProducer = "select timestamp, PacketId, flitId, isTail from " + name + " where moduleType='consumer'";
                rs = statement.executeQuery(queryProducer);
                while (rs.next()) {
                    double consumer_time = rs.getDouble(ConstantValue.TIMESTAMP);
                    int packetId = rs.getInt(ConstantValue.PACKETID);
                    int flitId = rs.getInt(ConstantValue.FLITID);
                    // 1: Tail 0: not Tail
                    int isTail = rs.getInt(ConstantValue.ISTAIL);
                    if (isTail != 1) {
                        packet_id_to_info_map.get(packetId).getFlit_ids().add(flitId);
                        continue;
                    }
                    if (packet_id_to_info_map.containsKey(packetId)) {
                        packet_id_to_info_map.get(packetId).setConsume_time(consumer_time);
                        packet_id_to_info_map.get(packetId).getFlit_ids().add(flitId);
                    } else {
                        System.out.println("packet id is not found! " + packetId);
                    }
                }
            }
            sqlUtils.closeConn(conn, statement, rs);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void parseProducer(List<String> table, String database) {
        Connection conn = null;
        try {
            conn = sqlUtils.getMySqlConnection(database);
            Statement statement = conn.createStatement();
            ResultSet rs = null;
            for (String name : table) {
                String queryProducer = "select timestamp, PacketId,flowId from " + name + " where moduleType='producer'";
                rs = statement.executeQuery(queryProducer);
                while (rs.next()) {
                    PacketTimingInfo packetTimingInfo = new PacketTimingInfo();
                    packetTimingInfo.setProduce_time(rs.getDouble(ConstantValue.TIMESTAMP));
                    packetTimingInfo.setPacket_id(rs.getInt(ConstantValue.PACKETID));
                    packetTimingInfo.setFlow_id(rs.getInt(ConstantValue.FLOWID));
                    packet_id_to_info_map.put(packetTimingInfo.getPacket_id(), packetTimingInfo);
                }
            }
            sqlUtils.closeConn(conn, statement, rs);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void initVariables() {
        analyzeResult.setLength(0);
        analyzeResult.append("Report All Flows:" + "\n");
        packet_id_to_info_map.clear();
        flow_id_to_analysis_map.clear();
        flowChartDataMap.clear();
    }


}
