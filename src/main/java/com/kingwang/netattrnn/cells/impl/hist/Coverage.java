/**   
 * @package	com.kingwang.cdmrnn.rnn
 * @File		Coverage.java
 * @Crtdate	Sep 28, 2016
 *
 * Copyright (c) 2016 by <a href="mailto:wangyongqing.casia@gmail.com">King Wang</a>.   
 */
package com.kingwang.netattrnn.cells.impl.hist;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.Map;

import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;

import com.kingwang.netattrnn.batchderv.BatchDerivative;
import com.kingwang.netattrnn.batchderv.impl.hist.CovBatchDerivative;
import com.kingwang.netattrnn.cells.Cell;
import com.kingwang.netattrnn.cells.Operator;
import com.kingwang.netattrnn.cells.impl.AttentionWithCov;
import com.kingwang.netattrnn.comm.utils.FileUtil;
import com.kingwang.netattrnn.cons.AlgConsHSoftmax;
import com.kingwang.netattrnn.utils.Activer;
import com.kingwang.netattrnn.utils.LoadTypes;
import com.kingwang.netattrnn.utils.MatIniter;
import com.kingwang.netattrnn.utils.MatIniter.Type;

/**
 *
 * @author King Wang
 * 
 * Sep 28, 2016 3:19:24 PM
 * @version 1.0
 */
public class Coverage extends Operator implements Cell, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3581712325656989072L;
	
	public DoubleMatrix Wvv;
	public DoubleMatrix Wav;
	public DoubleMatrix Whv;
	public DoubleMatrix Wtv;
	public DoubleMatrix bv;
	
	public DoubleMatrix hdWvv;
	public DoubleMatrix hdWav;
	public DoubleMatrix hdWhv;
	public DoubleMatrix hdWtv;
	public DoubleMatrix hdbv;
	
	public DoubleMatrix hd2Wvv;
	public DoubleMatrix hd2Wav;
	public DoubleMatrix hd2Whv;
	public DoubleMatrix hd2Wtv;
	public DoubleMatrix hd2bv;
	
	private int covSize;
	private int hiddenSize;
	
	public Coverage(int covSize, int attSize, int hiddenSize, MatIniter initer) {
		hdWvv = new DoubleMatrix(covSize, covSize);        
        hdWav = new DoubleMatrix(1, covSize);
        hdWhv = new DoubleMatrix(attSize, covSize);
        hdWtv = new DoubleMatrix(hiddenSize, covSize);
        hdbv = new DoubleMatrix(1, covSize);
        
        hd2Wvv = new DoubleMatrix(covSize, covSize);        
        hd2Wav = new DoubleMatrix(1, covSize);
        hd2Whv = new DoubleMatrix(attSize, covSize);
        hd2Wtv = new DoubleMatrix(hiddenSize, covSize);
        hd2bv = new DoubleMatrix(1, covSize);
		
		if (initer.getType() == Type.Uniform) {
			Wvv = initer.uniform(covSize, covSize);
        	Wav = initer.uniform(1, covSize);
        	Whv = initer.uniform(attSize, covSize);
        	Wtv = initer.uniform(hiddenSize, covSize);
        } else if (initer.getType() == Type.Gaussian) {
        	Wvv = initer.gaussian(covSize, covSize);
        	Wav = initer.gaussian(1, covSize);
        	Whv = initer.gaussian(attSize, covSize);
        	Wtv = initer.gaussian(hiddenSize, covSize);
        } else if (initer.getType() == Type.SVD) {
        	Wvv = initer.svd(covSize, covSize);
        	Wav = initer.svd(1, covSize);
        	Whv = initer.svd(attSize, covSize);
        	Wtv = initer.svd(hiddenSize, covSize);
        } 
		bv = new DoubleMatrix(1, covSize).add(AlgConsHSoftmax.biasInitVal);
		
		this.covSize = covSize;
		this.hiddenSize = hiddenSize;
	}

	public void active(int t, Map<String, DoubleMatrix> acts, double... params) {
		
		DoubleMatrix prevV = null;
		DoubleMatrix prevT = null;
		DoubleMatrix prevAlpha = null;
		if(t>0) {
			prevT = acts.get("t"+(t-1));
			prevV = acts.get("v"+(t-1));
			prevAlpha = acts.get("alpha"+(t-1));
		} else {
			prevT = DoubleMatrix.zeros(1, hiddenSize);
			prevV = DoubleMatrix.zeros(1, covSize);
			prevAlpha = DoubleMatrix.zeros(1);
		}
    	
		int eSize = Math.min(t+1, AlgConsHSoftmax.windowSize);
		int bsIdx = Math.max(0, t-AlgConsHSoftmax.windowSize+1);
		DoubleMatrix vecV = new DoubleMatrix(eSize, covSize);
		int biasPos = bsIdx>0?1:0;
		for(int k=bsIdx; k<t+1; k++) {
			DoubleMatrix hk = acts.get("h"+k);
			if(k<t) { //TODO:here is the problem
				vecV.putRow(k-bsIdx, Activer.logistic(prevV.getRow(k-bsIdx+biasPos).mmul(Wvv)
										.add(Wav.mul(prevAlpha.get(k-bsIdx+biasPos)))
										.add(hk.mmul(Whv)).add(prevT.mmul(Wtv)).add(bv)));
			} else {
				vecV.putRow(k-bsIdx, Activer.logistic(hk.mmul(Whv).add(prevT.mmul(Wtv)).add(bv)));
			}
		}
		
    	acts.put("v"+t, vecV);
	}
	
	public void bptt(Map<String, DoubleMatrix> acts, int lastT, Cell... cell) {

		AttentionWithCov att = (AttentionWithCov)cell[0];
		
		for (int t = lastT; t > -1; t--) { // no need to calculate the last one
        	DoubleMatrix deltaGs = acts.get("dgs"+t);
        	DoubleMatrix lateDgV = acts.get("dgV"+(t+1));
        	DoubleMatrix lateVecV = acts.get("v"+(t+1));
        	
        	int eSize = Math.min(t+1, AlgConsHSoftmax.windowSize);
    		int bsIdx = Math.max(0, t-AlgConsHSoftmax.windowSize+1);
    		int biasPos = bsIdx>0?1:0;
    		DoubleMatrix deltaGV = null;
			deltaGV = new DoubleMatrix(eSize, covSize);
			for(int k=bsIdx; k<t+1; k++) {
				deltaGV.putRow(k-bsIdx, deltaGs.getRow(k-bsIdx).mmul(att.Z.transpose()));
				if(k<t & lateDgV!=null) {
					deltaGV.putRow(k-bsIdx+biasPos, lateDgV.getRow(k-bsIdx)
								.mul(deriveExp(lateVecV.getRow(k-bsIdx))).mmul(Wvv.transpose()));
				}
			}
        	acts.put("dgV"+t, deltaGV);
    	}
		
		calcWeightsGradient(acts, lastT);
	}
	
	private void calcWeightsGradient(Map<String, DoubleMatrix> acts, int lastT) {
		
		DoubleMatrix dWvv = new DoubleMatrix(Wvv.rows, Wvv.columns);
    	DoubleMatrix dWav = new DoubleMatrix(Wav.rows, Wav.columns);
    	DoubleMatrix dWhv = new DoubleMatrix(Whv.rows, Whv.columns);
    	DoubleMatrix dWtv = new DoubleMatrix(Wtv.rows, Wtv.columns);
    	DoubleMatrix dbv = new DoubleMatrix(bv.rows, bv.columns);

    	for(int t=0; t<lastT+1; t++) { // no need to calculate the last one
			DoubleMatrix deltaGV = acts.get("dgV"+t);
			
			DoubleMatrix vecV = acts.get("v"+t);
			DoubleMatrix deltaGVevV = deltaGV.mul(deriveExp(vecV));
			int bsIdx = Math.max(0, t-AlgConsHSoftmax.windowSize+1);
			
			DoubleMatrix prevT = null;
			DoubleMatrix prevV = null;
			DoubleMatrix prevAlpha = null;
			if(t>0) {
				prevT = acts.get("t"+(t-1));
				prevV = acts.get("v"+(t-1));
				prevAlpha = acts.get("alpha"+(t-1));
			} else {
				prevT = DoubleMatrix.zeros(1, hiddenSize);
				prevV = DoubleMatrix.zeros(1, covSize);
				prevAlpha = DoubleMatrix.zeros(1);
			}

			for(int k=bsIdx; k<t+1; k++) {
				DoubleMatrix hk = acts.get("h"+k).transpose();
				if(k<t) {
					dWvv = dWvv.add(prevV.getRow(k-bsIdx).transpose().mmul(deltaGVevV.getRow(k-bsIdx)));
					dWav = dWav.add(deltaGVevV.getRow(k-bsIdx).mul(prevAlpha.get(k-bsIdx)));
				}
				dWhv = dWhv.add(hk.mmul(deltaGVevV.getRow(k-bsIdx)));
				dWtv = dWtv.add(prevT.transpose().mmul(deltaGVevV.getRow(k-bsIdx)));
				dbv = dbv.add(deltaGVevV.getRow(k-bsIdx));
			}
		}
		
		acts.put("dWvv", dWvv);
    	acts.put("dWav", dWav);
    	acts.put("dWhv", dWhv);
    	acts.put("dWtv", dWtv);
    	acts.put("dbv", dbv);
	}
	
	public void updateParametersByAdaGrad(BatchDerivative derv, double lr) {
    	
		CovBatchDerivative batchDerv = (CovBatchDerivative)derv;
    	
    	hdWvv = hdWvv.add(MatrixFunctions.pow(batchDerv.dWvv, 2.));
    	hdWav = hdWav.add(MatrixFunctions.pow(batchDerv.dWav, 2.));
    	hdWhv = hdWhv.add(MatrixFunctions.pow(batchDerv.dWhv, 2.));
    	hdWtv = hdWtv.add(MatrixFunctions.pow(batchDerv.dWtv, 2.));
    	hdbv = hdbv.add(MatrixFunctions.pow(batchDerv.dbv, 2.));
    	
        Wvv = Wvv.sub(batchDerv.dWvv.mul(
				MatrixFunctions.pow(MatrixFunctions.sqrt(hdWvv).add(eps),-1.).mul(lr)));
		Wav = Wav.sub(batchDerv.dWav.mul(
				MatrixFunctions.pow(MatrixFunctions.sqrt(hdWav).add(eps),-1.).mul(lr)));
		Whv = Whv.sub(batchDerv.dWhv.mul(
				MatrixFunctions.pow(MatrixFunctions.sqrt(hdWhv).add(eps),-1.).mul(lr)));
		Wtv = Wtv.sub(batchDerv.dWtv.mul(
				MatrixFunctions.pow(MatrixFunctions.sqrt(hdWtv).add(eps),-1.).mul(lr)));
		bv = bv.sub(batchDerv.dbv.mul(
				MatrixFunctions.pow(MatrixFunctions.sqrt(hdbv).add(eps),-1.).mul(lr)));
    }
    
    public void updateParametersByAdam(BatchDerivative derv, double lr
    						, double beta1, double beta2, int epochT) {
    	
    	CovBatchDerivative batchDerv = (CovBatchDerivative)derv;

		double biasBeta1 = 1. / (1 - Math.pow(beta1, epochT));
		double biasBeta2 = 1. / (1 - Math.pow(beta2, epochT));

		hd2Wvv = hd2Wvv.mul(beta2).add(MatrixFunctions.pow(batchDerv.dWvv, 2.).mul(1 - beta2));
		hd2Wav = hd2Wav.mul(beta2).add(MatrixFunctions.pow(batchDerv.dWav, 2.).mul(1 - beta2));
		hd2Whv = hd2Whv.mul(beta2).add(MatrixFunctions.pow(batchDerv.dWhv, 2.).mul(1 - beta2));
		hd2Wtv = hd2Wtv.mul(beta2).add(MatrixFunctions.pow(batchDerv.dWtv, 2.).mul(1 - beta2));
		hd2bv = hd2bv.mul(beta2).add(MatrixFunctions.pow(batchDerv.dbv, 2.).mul(1 - beta2));
		
		hdWvv = hdWvv.mul(beta1).add(batchDerv.dWvv.mul(1 - beta1));
		hdWav = hdWav.mul(beta1).add(batchDerv.dWav.mul(1 - beta1));
		hdWhv = hdWhv.mul(beta1).add(batchDerv.dWhv.mul(1 - beta1));
		hdWtv = hdWtv.mul(beta1).add(batchDerv.dWtv.mul(1 - beta1));
		hdbv = hdbv.mul(beta1).add(batchDerv.dbv.mul(1 - beta1));
		
		Wvv = Wvv.sub(
				hdWvv.mul(biasBeta1).mul(lr)
				.mul(MatrixFunctions.pow(MatrixFunctions.sqrt(hd2Wvv.mul(biasBeta2)).add(eps), -1))
				);
		Wav = Wav.sub(
				hdWav.mul(biasBeta1).mul(lr)
				.mul(MatrixFunctions.pow(MatrixFunctions.sqrt(hd2Wav.mul(biasBeta2)).add(eps), -1))
				);
		Whv = Whv.sub(
				hdWhv.mul(biasBeta1).mul(lr)
				.mul(MatrixFunctions.pow(MatrixFunctions.sqrt(hd2Whv.mul(biasBeta2)).add(eps), -1))
				);
		Wtv = Wtv.sub(
				hdWtv.mul(biasBeta1).mul(lr)
				.mul(MatrixFunctions.pow(MatrixFunctions.sqrt(hd2Wtv.mul(biasBeta2)).add(eps), -1))
				);
		bv = bv.sub(
				hdbv.mul(biasBeta1).mul(lr)
				.mul(MatrixFunctions.pow(MatrixFunctions.sqrt(hd2bv.mul(biasBeta2)).add(eps), -1))
				);
    }

	/* (non-Javadoc)
	 * @see com.kingwang.cdmrnn.rnn.Cell#writeCellParameter(java.lang.String, boolean)
	 */
	@Override
	public void writeCellParameter(String outFile, boolean isAttached) {
		OutputStreamWriter osw = FileUtil.getOutputStreamWriter(outFile, isAttached);
		FileUtil.writeln(osw, "Wvv");
		writeMatrix(osw, Wvv);
		FileUtil.writeln(osw, "Wav");
		writeMatrix(osw, Wav);
		FileUtil.writeln(osw, "Whv");
		writeMatrix(osw, Whv);
		FileUtil.writeln(osw, "Wtv");
		writeMatrix(osw, Wtv);
		FileUtil.writeln(osw, "bv");
		writeMatrix(osw, bv);
	}

	/* (non-Javadoc)
	 * @see com.kingwang.cdmrnn.rnn.Cell#loadCellParameter(java.lang.String)
	 */
	@Override
	public void loadCellParameter(String cellParamFile) {
		LoadTypes type = LoadTypes.Null;
		int row = 0;
		
		try(BufferedReader br = FileUtil.getBufferReader(cellParamFile)) {
			String line = null;
			while((line=br.readLine())!=null) {
				String[] elems = line.split(",");
				if(elems.length<2 && !elems[0].contains(".")) {
					String typeStr = "Null";
    				String[] typeLitv = {"Wvv", "Wav", "Whv", "Wtv", "bv"};
    				for(String tStr : typeLitv) {
    					if(elems[0].equalsIgnoreCase(tStr)) {
    						typeStr = tStr;
    						break;
    					}
    				}
    				type = LoadTypes.valueOf(typeStr);
					row = 0;
					continue;
				}
				switch(type) {
					case Wvv: this.Wvv = matrixSetter(row, elems, this.Wvv); break;
					case Wav: this.Wav = matrixSetter(row, elems, this.Wav); break;
					case Whv: this.Whv = matrixSetter(row, elems, this.Whv); break;
					case Wtv: this.Wtv = matrixSetter(row, elems, this.Wtv); break;
					case bv: this.bv = matrixSetter(row, elems, this.bv); break;
				}
				row++;
			}
			
		} catch(IOException e) {
			
		}
	}
}