/**   
 * @package	com.kingwang.rnncdm.evals
 * @File		RNNModelMRREvals.java
 * @Crtdate	May 22, 2016
 *
 * Copyright (c) 2016 by <a href="mailto:wangyongqing.casia@gmail.com">King Wang</a>.   
 */
package com.kingwang.netattrnn.evals;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jblas.DoubleMatrix;

import com.kingwang.netattrnn.cells.impl.GRU;
import com.kingwang.netattrnn.cells.impl.InputLayer;
import com.kingwang.netattrnn.cells.impl.OutputLayerWithHSoftMax;
import com.kingwang.netattrnn.cells.impl.hist.Attention_alphaReg;
import com.kingwang.netattrnn.comm.utils.CollectionHelper;
import com.kingwang.netattrnn.comm.utils.Config;
import com.kingwang.netattrnn.comm.utils.FileUtil;
import com.kingwang.netattrnn.comm.utils.StringHelper;
import com.kingwang.netattrnn.cons.AlgConsHSoftmax;
import com.kingwang.netattrnn.cons.MultiThreadCons;
import com.kingwang.netattrnn.dataset.DataLoader;
import com.kingwang.netattrnn.dataset.Node4Code;
import com.kingwang.netattrnn.dataset.SeqLoader;
import com.kingwang.netattrnn.utils.Activer;
import com.kingwang.netattrnn.utils.InputEncoder;
import com.kingwang.netattrnn.utils.LossFunction;
import com.kingwang.netattrnn.utils.MatIniter;
import com.kingwang.netattrnn.utils.MatIniter.Type;
import com.kingwang.netattrnn.utils.TmFeatExtractor;

/**
 *
 * @author King Wang
 * 
 * May 22, 2016 5:03:33 PM
 * @version 1.0
 */
public class CyanRNNNoInputModelEvals {
	
	public static Double logLkHd = .0;
	public static Double mrr = .0;
	public GRU gru;
	public Attention_alphaReg att;
	public OutputLayerWithHSoftMax output;
	public DataLoader casLoader;
	public OutputStreamWriter oswLog;
	
	public CyanRNNNoInputModelEvals(GRU gru, Attention_alphaReg att, OutputLayerWithHSoftMax output
						, DataLoader casLoader, OutputStreamWriter oswLog) {
		this.gru = gru;
		this.att = att;
		this.output = output;
		this.casLoader = casLoader;
		this.oswLog = oswLog;
	}
	
	private void calcGradientByMiniBatch(List<String> sequence) {
		
    	MultiThreadCons.missions = getMissions(sequence);
    	MultiThreadCons.missionSize = sequence.size();
    	MultiThreadCons.missionOver = 0;
    	
		ExecutorService exec = Executors.newCachedThreadPool();
		for (int i = 0; i < MultiThreadCons.threadNum; i++) {
			exec.execute(new Exec());
		}
		while (MultiThreadCons.missionOver!=MultiThreadCons.threadNum) {
			try {
				Thread.sleep((long) (1000 * MultiThreadCons.sleepSec));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		exec.shutdown();
		try {
			exec.awaitTermination(500, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private List<String> getMissions(List<String> sequence) {
    	
    	List<String> missions = new ArrayList<>();
    	for(String seq : sequence) {
    		missions.add(seq);
    	}
    	
    	return missions;
    }
	
	public double validationOnIntegration() {

		List<String> crsValSeq = casLoader.getCrsValSeq();
		logLkHd = .0;
		mrr = .0;
    	calcGradientByMiniBatch(crsValSeq);
		
		logLkHd /= crsValSeq.size();
		mrr /= crsValSeq.size();
		System.out.println("The likelihood in Validation: " + logLkHd);
		FileUtil.writeln(oswLog, "The likelihood in Validation: " + logLkHd);
		System.out.println("The MRR of node prediction in Validation: " + mrr);
		FileUtil.writeln(oswLog, "The MRR of node prediction in Validation: " + mrr);

		return logLkHd;
	}
	
	class Exec implements Runnable {

		private void mainProc(String seq) {
			
			Map<String, DoubleMatrix> acts = new HashMap<String, DoubleMatrix>();
			List<String> infos = SeqLoader.getNodesAndTimesFromMeme(seq);
			if(infos.size()<3) { //skip short cascades
            	return;
            }
            String iid = infos.remove(0);
            double cas_logLkHd=0, cas_mrr=0, prevTm=0;
			int missCnt = 0;
//			String wrtLn = iid+",";
			for (int t = 0; t < infos.size() - 1; t++) {
				String[] curInfo = infos.get(t).split(",");
				String[] nextInfo = infos.get(t + 1).split(",");
				// translating string node to node index in repMatrix
				String curNd = curInfo[0];
            	String nxtNd = nextInfo[0];
            	double curTm = Double.parseDouble(curInfo[1]);
            	if(!casLoader.getCodeMaps().containsKey(curNd)) {//if curNd isn't located in nodeDict
//	            		System.out.println("Missing node"+curNd);
            		missCnt++;
            		curNd = "null";
            		break;//TODO: how to solve "null" node
            	}
            	if(!casLoader.getCodeMaps().containsKey(nxtNd)) {//if curNd isn't located in nodeDict
            		curNd = "null";
            		break;//TODO: how to solve "null" node
            	}
            	Node4Code nxtNd4Code = casLoader.getCodeMaps().get(nxtNd);
            	//Set DoubleMatrix code & fixedFeat. It should be a code setter function here.
//            	DoubleMatrix tmFeat = TmFeatExtractor.timeFeatExtractor(curTm, prevTm);
            	DoubleMatrix tmFeat = new DoubleMatrix(1);
            	DoubleMatrix fixedFeat;
				try {
					DoubleMatrix code = new DoubleMatrix(AlgConsHSoftmax.nodeSize);
					code.put(Integer.parseInt(curNd), 1.);
					fixedFeat = InputEncoder.setFixedFeat(t, AlgConsHSoftmax.inFixedSize, tmFeat, code);
					acts.put("fixedFeat"+t, fixedFeat);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					break;
				}
				
				int nodeCls = nxtNd4Code.nodeCls;
            	
				DoubleMatrix x = new DoubleMatrix(AlgConsHSoftmax.inDynSize);
            	acts.put("x"+t, x);
                gru.active(t, acts);
                att.active(t, acts);
                output.active(t, acts, nodeCls);
            	
                //actual u
                int nxtNdIdxInCls = nxtNd4Code.idxInCls;
                DoubleMatrix y = new DoubleMatrix(1, AlgConsHSoftmax.nodeSize);
                y.put(nxtNdIdxInCls, 1);
    	        acts.put("y" + t, y);
    	        
    	        DoubleMatrix py = acts.get("py"+t);
    	        DoubleMatrix pc = acts.get("pc"+t);
                cas_logLkHd -= Math.log(py.get(nxtNdIdxInCls))/(infos.size()-1);
                cas_logLkHd -= Math.log(pc.get(nodeCls))/(infos.size()-1);

                DoubleMatrix prob = py.mul(pc.get(nodeCls));
                DoubleMatrix[] otherProb = new DoubleMatrix[AlgConsHSoftmax.cNum-1];
                int cCnt = 0;
                for(int c=0; c<AlgConsHSoftmax.cNum; c++) {
                	if(c==nodeCls) {
                		continue;
                	}
                	DoubleMatrix s = acts.get("s"+t);
                	DoubleMatrix hatYt = s.mmul(output.Wsy[c]).add(output.by[c]);
                    DoubleMatrix predictYt = Activer.softmax(hatYt);
                    otherProb[cCnt] = predictYt.mul(pc.get(c));
                    cCnt++;
                }
                double mrr = LossFunction.calcMRR(prob, nxtNdIdxInCls, otherProb);
                cas_mrr += mrr/(infos.size()-1);
                
//                wrtLn += nxtNd+","+mrr+",";
                
                prevTm = curTm;
			}
			synchronized(logLkHd) {
				logLkHd += cas_logLkHd;
			}
			synchronized(mrr) {
				mrr -= cas_mrr;
			}
//			synchronized(oswLog) {
//				FileUtil.writeln(oswLog, wrtLn.substring(0, wrtLn.length()-1));
//			}
		}
		
		private String consumeMissions() {
    		synchronized(MultiThreadCons.missions) {
    			if(!MultiThreadCons.missions.isEmpty()) {
    				return MultiThreadCons.missions.remove(0);
    			} else {
    				return null;
    			}
    		}
    	}
		
		private void missionOver() {
			
			boolean isCompleted = false;
			while(!isCompleted) {
				synchronized(MultiThreadCons.canRevised) {
					if(MultiThreadCons.canRevised) {
						MultiThreadCons.canRevised = false;
						synchronized(MultiThreadCons.missionOver) {
							MultiThreadCons.missionOver++;
							MultiThreadCons.canRevised = true;
							isCompleted = true;
						}
					}
				}
			}
		}
		
		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run() {
			while(!CollectionHelper.isEmpty(MultiThreadCons.missions)) {
				String seq = consumeMissions();
				if(StringHelper.isEmpty(seq)) {
					continue;
				}
				mainProc(seq);
			}
			
			missionOver();
		}
	}
	
	public static void main(String[] args) {
		
		if(args.length<1) {
    		System.out.println("Please input configuration file");
    		return;
    	}

    	try {
    		Map<String, String> config = Config.getConfParams(args[0]);
    		//Files
    		AlgConsHSoftmax.casFile = config.get("cas_file");
    		AlgConsHSoftmax.crsValFile = config.get("crs_val_file");
    		AlgConsHSoftmax.isContTraining = Boolean.parseBoolean(config.get("is_cont_training"));
    		if(AlgConsHSoftmax.isContTraining) {
    			AlgConsHSoftmax.lastModelFile = config.get("last_rnn_model");
    		}
    		AlgConsHSoftmax.windowSize = Integer.parseInt(config.get("window_size"));
    		AlgConsHSoftmax.tmDiv = Double.parseDouble(config.get("time_div"));
			AlgConsHSoftmax.freqFile = config.get("freq_file");
    		AlgConsHSoftmax.outFile = config.get("out_file");
    		AlgConsHSoftmax.rnnType = config.get("rnn_type");
    		AlgConsHSoftmax.trainStrategy = config.get("train_strategy");
    		//Model Parameters
    		AlgConsHSoftmax.cNum = Integer.parseInt(config.get("class_num"));
    		AlgConsHSoftmax.nodeSize = Integer.parseInt(config.get("node_size"));
    		AlgConsHSoftmax.inFixedSize = Integer.parseInt(config.get("in_fixed_size"));
    		AlgConsHSoftmax.inDynSize = Integer.parseInt(config.get("in_dyn_size"));
    		AlgConsHSoftmax.attSize = Integer.parseInt(config.get("att_size"));
    		AlgConsHSoftmax.hiddenSize = Integer.parseInt(config.get("hidden_size"));
    		//System settings
    		MultiThreadCons.threadNum = Integer.parseInt(config.get("thread_num"));
    		MultiThreadCons.sleepSec = Double.parseDouble(config.get("sleep_sec"));
    		
    		Config.printConf(config, "log");
    	} catch(IOException e) {}
    	
    	MatIniter initer = new MatIniter(Type.SVD);
    	
    	DataLoader casLoader = new DataLoader(AlgConsHSoftmax.casFile, AlgConsHSoftmax.crsValFile
    			, AlgConsHSoftmax.freqFile, AlgConsHSoftmax.cNum);
    	
    	GRU gru = new GRU(AlgConsHSoftmax.inDynSize, AlgConsHSoftmax.inFixedSize, AlgConsHSoftmax.hiddenSize, initer);
    	Attention_alphaReg att = new Attention_alphaReg(AlgConsHSoftmax.inDynSize, AlgConsHSoftmax.inFixedSize
    									, AlgConsHSoftmax.attSize, AlgConsHSoftmax.hiddenSize, initer);
    	OutputLayerWithHSoftMax output = new OutputLayerWithHSoftMax(AlgConsHSoftmax.inDynSize
    											, AlgConsHSoftmax.inFixedSize, AlgConsHSoftmax.attSize
    											, AlgConsHSoftmax.hiddenSize, AlgConsHSoftmax.cNum, initer);
    	
    	OutputStreamWriter oswLog = FileUtil.getOutputStreamWriter(AlgConsHSoftmax.outFile);
    	
    	if(AlgConsHSoftmax.isContTraining) {
    		gru.loadCellParameter(AlgConsHSoftmax.lastModelFile);
    		att.loadCellParameter(AlgConsHSoftmax.lastModelFile);
    		output.loadCellParameter(AlgConsHSoftmax.lastModelFile);
    	} 

    	CyanRNNNoInputModelEvals eval = new CyanRNNNoInputModelEvals(gru, att, output, casLoader, oswLog);
    	
    	eval.validationOnIntegration();
	}
}
