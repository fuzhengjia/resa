package resa.evaluation.topology.tomVLD;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static resa.evaluation.topology.tomVLD.Constants.*;

/**
 * Created by Tom Fu at Mar 24, 2015
 * This bolt is designed to collect the found rects from the Patch Process bolt,
 * When all the found rect message (the found rect data can be null) is received (#messages == patch_count)
 * It sends out the foundRectList to the patchDraw bolt
 * when sampleFrame (denoted by N) > 1, actually, only the Nth frame will be processed (and also generate related patches)
 * while the other frames will be just input and draw the founded rects, generated by the nearest Nth frame
 * (which means, the rects generated at the Nth frame, will last for N )
 *
 * Updated on April 29, the way to handle frame sampling issue is changed, this is pre-processed by the spout not to
 * send out unsampled frames to the patch generation bolt.
 */
public class PatchAggForExp extends BaseRichBolt {
    OutputCollector collector;

    /* Keeps track on which patches of the certain frame have already been received */
    //Map< Integer, HashSet<Serializable.Rect> > frameAccount;
    Map< Integer, Integer> frameMonitor;

    /* Contains the list of logos found found on a given frame */
    Map< Integer, List<List<Serializable.Rect>> > foundRectAccount;
    private int sampleFrames;

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.collector = outputCollector;
        frameMonitor = new HashMap<>();
        foundRectAccount = new HashMap<>();
        sampleFrames = ConfigUtil.getInt(map, "sampleFrames", 1);
    }

    @Override
    public void execute(Tuple tuple) {

        //opencv_core.IplImage fk = new opencv_core.IplImage();
        int frameId = tuple.getIntegerByField(FIELD_FRAME_ID);
        int patchCount              = tuple.getIntegerByField(FIELD_PATCH_COUNT);
        List<Serializable.Rect> foundRect = (List<Serializable.Rect>)tuple.getValueByField(FIELD_FOUND_RECT);

        if (!foundRectAccount.containsKey(frameId)){
            foundRectAccount.put(frameId, new ArrayList<>());
            for (int logoIndex = 0; logoIndex < foundRect.size(); logoIndex ++) {
                foundRectAccount.get(frameId).add(new ArrayList<>());
            }
        }
        /* Updating the list of detected logos on the frame */
        for (int logoIndex = 0; logoIndex < foundRect.size(); logoIndex ++) {
            if (foundRect.get(logoIndex) != null) {
                foundRectAccount.get(frameId).get(logoIndex).add(foundRect.get(logoIndex));
            }
        }
        frameMonitor.computeIfAbsent(frameId, k->0);
        frameMonitor.computeIfPresent(frameId, (k,v)->v+1);;

        /* If all patches of this frame are collected proceed to the frame aggregator */
        if (frameMonitor.get(frameId) == patchCount) {

            if (frameId % sampleFrames == 0) {
                for (int f = frameId; f < frameId + sampleFrames; f ++){
                    collector.emit(PROCESSED_FRAME_STREAM, new Values(f, foundRectAccount.get(frameId)));
                }
            } else {//shall not be here!
                throw new IllegalArgumentException("frameId % sampleFrames != 0, frameID: " + frameId + ", sampleFrames: " + sampleFrames);
            }

            frameMonitor.remove(frameId);
            foundRectAccount.remove(frameId);
        }
        collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(PROCESSED_FRAME_STREAM, new Fields(FIELD_FRAME_ID, FIELD_FOUND_RECT_LIST));
    }
}