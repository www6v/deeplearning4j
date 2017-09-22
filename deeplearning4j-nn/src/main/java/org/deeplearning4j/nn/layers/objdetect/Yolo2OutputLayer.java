package org.deeplearning4j.nn.layers.objdetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.activations.Activations;
import org.deeplearning4j.nn.api.activations.ActivationsFactory;
import org.deeplearning4j.nn.api.gradients.Gradients;
import org.deeplearning4j.nn.api.gradients.GradientsFactory;
import org.deeplearning4j.nn.api.layers.IOutputLayer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.AbstractLayer;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.activations.impl.ActivationIdentity;
import org.nd4j.linalg.activations.impl.ActivationSigmoid;
import org.nd4j.linalg.activations.impl.ActivationSoftmax;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastMulOp;
import org.nd4j.linalg.api.ops.impl.transforms.IsMax;
import org.nd4j.linalg.api.ops.impl.transforms.Not;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Broadcast;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.BooleanIndexing;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.lossfunctions.impl.LossL2;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.nd4j.linalg.primitives.Pair;

import java.io.Serializable;
import java.util.*;

import static org.nd4j.linalg.indexing.NDArrayIndex.*;

/**
 * Output (loss) layer for YOLOv2 object detection model, based on the papers:
 * YOLO9000: Better, Faster, Stronger - Redmon & Farhadi (2016) - https://arxiv.org/abs/1612.08242<br>
 * and<br>
 * You Only Look Once: Unified, Real-Time Object Detection - Redmon et al. (2016) -
 * http://www.cv-foundation.org/openaccess/content_cvpr_2016/papers/Redmon_You_Only_Look_CVPR_2016_paper.pdf<br>
 * <br>
 * This loss function implementation is based on the YOLOv2 version of the paper. However, note that it doesn't
 * currently support simultaneous training on both detection and classification datasets as described in the
 * YOlO9000 paper.<br>
 * <br>
 * Label format: [minibatch, 4+C, H, W]<br>
 * Order for labels depth: [x1,y1,x2,y2,(class labels)]<br>
 * x1 = box top left position<br>
 * y1 = as above, y axis<br>
 * x2 = box bottom right position<br>
 * y2 = as above y axis<br>
 * Note: labels are represented as a multiple of grid size - for a 13x13 grid, (0,0) is top left, (13,13) is bottom right<br>
 * <br>
 * Input format: [minibatch, B*(5+C), H, W]    ->      Reshape to [minibatch, B, 5+C, H, W]<br>
 * B = number of bounding boxes (determined by config)<br>
 * C = number of classes<br>
 * H = output/label height<br>
 * W = output/label width<br>
 * <br>
 * Note that mask arrays are not required - this implementation infers the presence or absence of objects in each grid
 * cell from the class labels (which should be 1-hot if an object is present, or all 0s otherwise).
 *
 * @author Alex Black
 */
public class Yolo2OutputLayer extends AbstractLayer<org.deeplearning4j.nn.conf.layers.objdetect.Yolo2OutputLayer> implements Serializable, IOutputLayer {
    private static final Gradient EMPTY_GRADIENT = new DefaultGradient();

    @Getter
    protected INDArray labels;

    private double fullNetworkL1;
    private double fullNetworkL2;
    private double score;

    public Yolo2OutputLayer(NeuralNetConfiguration conf) {
        super(conf);
    }

    @Override
    public Gradients backpropGradient(Gradients epsilon) {
        INDArray epsOut = computeBackpropGradientAndScore();

        Gradients g = GradientsFactory.getInstance().create(epsOut, EMPTY_GRADIENT);
        return backpropPreprocessor(g);
    }

    private INDArray computeBackpropGradientAndScore(){
        INDArray input = this.input.get(0);

        double lambdaCoord = layerConf().getLambdaCoord();
        double lambdaNoObj = layerConf().getLambdaNoObj();

        int mb = input.size(0);
        int h = input.size(2);
        int w = input.size(3);
        int b = layerConf().getBoundingBoxes().size(0);
        int c = labels.size(1)-4;

        //Various shape arrays, to reuse
        int[] nhw = new int[]{mb, h, w};
        int[] nbhw = new int[]{mb, b, h, w};

        //Labels shape: [mb, 4+C, H, W]
        //Infer mask array from labels. Mask array is 1_i^B in YOLO paper - i.e., whether an object is present in that
        // grid location or not. Here: we are using the fact that class labels are one-hot, and assume that values are
        // all 0s if no class label is present
        int size1 = labels.size(1);
        INDArray classLabels = labels.get(all(), interval(4,size1), all(), all());   //Shape: [minibatch, nClasses, H, W]
        INDArray maskObjectPresent = classLabels.sum(Nd4j.createUninitialized(nhw, 'c'), 1); //Shape: [minibatch, H, W]

        // ----- Step 1: Labels format conversion -----
        //First: Convert labels/ground truth (x1,y1,x2,y2) from "coordinates (grid box units)" format to "center position in grid box" format
        //0.5 * ([x1,y1]+[x2,y2])   ->      shape: [mb, B, 2, H, W]
        INDArray labelTLXY = labels.get(all(), interval(0,2), all(), all());
        INDArray labelBRXY = labels.get(all(), interval(2,4), all(), all());

        INDArray labelCenterXY = labelTLXY.add(labelBRXY).muli(0.5);  //In terms of grid units
        INDArray labelsCenterXYInGridBox = labelCenterXY.dup(labelCenterXY.ordering());         //[mb, 2, H, W]
        labelsCenterXYInGridBox.subi(Transforms.floor(labelsCenterXYInGridBox,true));

        //Also infer size/scale (label w/h) from (x1,y1,x2,y2) format to (w,h) format
        INDArray labelWHSqrt = labelBRXY.sub(labelTLXY);
        labelWHSqrt = Transforms.sqrt(labelWHSqrt, false);



        // ----- Step 2: apply activation functions to network output activations -----
        //Reshape from [minibatch, B*(5+C), H, W] to [minibatch, B, 5+C, H, W]
        INDArray input5 = input.dup('c').reshape('c', mb, b, 5+c, h, w);
        INDArray inputClassesPreSoftmax = input5.get(all(), all(), interval(5, 5+c), all(), all());

        // Sigmoid for x/y centers
        INDArray preSigmoidPredictedXYCenterGrid = input5.get(all(), all(), interval(0,2), all(), all());
        INDArray predictedXYCenterGrid = Transforms.sigmoid(preSigmoidPredictedXYCenterGrid, true); //Not in-place, need pre-sigmoid later

        //Exponential for w/h (for: boxPrior * exp(input))      ->      Predicted WH in grid units (0 to 13 usually)
        INDArray predictedWHPreExp = input5.get(all(), all(), interval(2,4), all(), all());
        INDArray predictedWH = Transforms.exp(predictedWHPreExp, true);
        Broadcast.mul(predictedWH, layerConf().getBoundingBoxes(), predictedWH, 1, 2);  //Box priors: [b, 2]; predictedWH: [mb, b, 2, h, w]

        //Apply sqrt to W/H in preparation for loss function
        INDArray predictedWHSqrt = Transforms.sqrt(predictedWH, true);



        // ----- Step 3: Calculate IOU(predicted, labels) to infer 1_ij^obj mask array (for loss function) -----
        //Calculate IOU (intersection over union - aka Jaccard index) - for the labels and predicted values
        IOURet iouRet = calculateIOULabelPredicted(labelTLXY, labelBRXY, predictedWH, predictedXYCenterGrid, maskObjectPresent);  //IOU shape: [minibatch, B, H, W]
        INDArray iou = iouRet.getIou();

        //Mask 1_ij^obj: isMax (dimension 1) + apply object present mask. Result: [minibatch, B, H, W]
        //In this mask: 1 if (a) object is present in cell [for each mb/H/W], AND (b) it is the box with the highest
        // IOU of any in the grid cell
        //We also need 1_ij^noobj, which is (a) no object, or (b) object present in grid cell, but this box doesn't
        // have the highest IOU
        INDArray mask1_ij_obj = Nd4j.getExecutioner().execAndReturn(new IsMax(iou.dup('c'), 1));
        Nd4j.getExecutioner().execAndReturn(new BroadcastMulOp(mask1_ij_obj, maskObjectPresent, mask1_ij_obj, 0,2,3));
        INDArray mask1_ij_noobj = Transforms.not(mask1_ij_obj);



        // ----- Step 4: Calculate confidence, and confidence label -----
        //Predicted confidence: sigmoid (0 to 1)
        //Label confidence: 0 if no object, IOU(predicted,actual) if an object is present
        INDArray labelConfidence = iou.mul(mask1_ij_obj);  //Need to reuse IOU array later. IOU Shape: [mb, B, H, W]
        INDArray predictedConfidencePreSigmoid = input5.get(all(), all(), point(4), all(), all());    //Shape: [mb, B, H, W]
        INDArray predictedConfidence = Transforms.sigmoid(predictedConfidencePreSigmoid, true);



        // ----- Step 5: Loss Function -----
        //One design goal here is to make the loss function configurable. To do this, we want to reshape the activations
        //(and masks) to a 2d representation, suitable for use in DL4J's loss functions

        INDArray mask1_ij_obj_2d = mask1_ij_obj.reshape(mb*b*h*w, 1);  //Must be C order before reshaping
        INDArray mask1_ij_noobj_2d = Transforms.not(mask1_ij_obj_2d);   //Not op is copy op; mask has 1 where box is not responsible for prediction

        INDArray predictedXYCenter2d = predictedXYCenterGrid.permute(0,1,3,4,2)  //From: [mb, B, 2, H, W] to [mb, B, H, W, 2]
                .dup('c').reshape('c', mb*b*h*w, 2);
        //Don't use INDArray.broadcast(int...) until ND4J issue is fixed: https://github.com/deeplearning4j/nd4j/issues/2066
        //INDArray labelsCenterXYInGridBroadcast = labelsCenterXYInGrid.broadcast(mb, b, 2, h, w);
        //Broadcast labelsCenterXYInGrid from [mb, 2, h, w} to [mb, b, 2, h, w]
        INDArray labelsCenterXYInGridBroadcast = Nd4j.createUninitialized(new int[]{mb, b, 2, h, w}, 'c');
        for(int i=0; i<b; i++ ){
            labelsCenterXYInGridBroadcast.get(all(), point(i), all(), all(), all()).assign(labelsCenterXYInGridBox);
        }
        INDArray labelXYCenter2d = labelsCenterXYInGridBroadcast.permute(0,1,3,4,2).dup('c').reshape('c', mb*b*h*w, 2);    //[mb, b, 2, h, w] to [mb, b, h, w, 2] to [mb*b*h*w, 2]

        //Width/height (sqrt)
        INDArray predictedWHSqrt2d = predictedWHSqrt.permute(0,1,3,4,2).dup('c').reshape(mb*b*h*w, 2).dup('c'); //from [mb, b, 2, h, w] to [mb, b, h, w, 2] to [mb*b*h*w, 2]
        //Broadcast labelWHSqrt from [mb, 2, h, w} to [mb, b, 2, h, w]
        INDArray labelWHSqrtBroadcast = Nd4j.createUninitialized(new int[]{mb, b, 2, h, w}, 'c');
        for(int i=0; i<b; i++ ){
            labelWHSqrtBroadcast.get(all(), point(i), all(), all(), all()).assign(labelWHSqrt); //[mb, 2, h, w] to [mb, b, 2, h, w]
        }
        INDArray labelWHSqrt2d = labelWHSqrtBroadcast.permute(0,1,3,4,2).dup('c').reshape(mb*b*h*w, 2).dup('c');   //[mb, b, 2, h, w] to [mb, b, h, w, 2] to [mb*b*h*w, 2]

        //Confidence
        INDArray labelConfidence2d = labelConfidence.dup('c').reshape('c', mb * b * h * w, 1);
        INDArray predictedConfidence2d = predictedConfidence.dup('c').reshape('c', mb * b * h * w, 1).dup('c');
        INDArray predictedConfidence2dPreSigmoid = predictedConfidencePreSigmoid.dup('c').reshape('c', mb * b * h * w, 1).dup('c');


        //Class prediction loss
        INDArray classPredictionsPreSoftmax2d = inputClassesPreSoftmax.permute(0,1,3,4,2) //[minibatch, b, c, h, w] To [mb, b, h, w, c]
                .dup('c').reshape('c', new int[]{mb*b*h*w, c});
        INDArray classLabelsBroadcast = Nd4j.createUninitialized(new int[]{mb, b, c, h, w}, 'c');
        for(int i=0; i<b; i++ ){
            classLabelsBroadcast.get(all(), point(i), all(), all(), all()).assign(classLabels); //[mb, c, h, w] to [mb, b, c, h, w]
        }
        INDArray classLabels2d = classLabelsBroadcast.permute(0,1,3,4,2).dup('c').reshape('c', new int[]{mb*b*h*w, c});

        //Calculate the loss:
        ILossFunction lossConfidence = new LossL2();
        IActivation identity = new ActivationIdentity();
        double positionLoss = layerConf().getLossPositionScale().computeScore(labelXYCenter2d, predictedXYCenter2d, identity, mask1_ij_obj_2d, false );
        double sizeScaleLoss = layerConf().getLossPositionScale().computeScore(labelWHSqrt2d, predictedWHSqrt2d, identity, mask1_ij_obj_2d, false);
        double confidenceLoss = lossConfidence.computeScore(labelConfidence2d, predictedConfidence2d, identity, mask1_ij_obj_2d, false)
                + lambdaNoObj * lossConfidence.computeScore(labelConfidence2d, predictedConfidence2d, identity, mask1_ij_noobj_2d, false);    //TODO: possible to optimize this?
        double classPredictionLoss = layerConf().getLossClassPredictions().computeScore(classLabels2d, classPredictionsPreSoftmax2d, new ActivationSoftmax(), mask1_ij_obj_2d, false);

        this.score = lambdaCoord * (positionLoss + sizeScaleLoss) +
                confidenceLoss  +
                classPredictionLoss +
                fullNetworkL1 +
                fullNetworkL2;

        this.score /= getInputMiniBatchSize();


        //==============================================================
        // ----- Gradient Calculation (specifically: return dL/dIn -----

        INDArray epsOut = Nd4j.create(input.shape(), 'c');
        INDArray epsOut5 = Shape.newShapeNoCopy(epsOut, new int[]{mb, b, 5+c, h, w}, false);
        INDArray epsClassPredictions = epsOut5.get(all(), all(), interval(5, 5+c), all(), all());    //Shape: [mb, b, 5+c, h, w]
        INDArray epsXY = epsOut5.get(all(), all(), interval(0,2), all(), all());
        INDArray epsWH = epsOut5.get(all(), all(), interval(2,4), all(), all());
        INDArray epsC = epsOut5.get(all(), all(), point(4), all(), all());


        //Calculate gradient component from class probabilities (softmax)
        //Shape: [minibatch*h*w, c]
        INDArray gradPredictionLoss2d = layerConf().getLossClassPredictions().computeGradient(classLabels2d, classPredictionsPreSoftmax2d, new ActivationSoftmax(), mask1_ij_obj_2d);
        INDArray gradPredictionLoss5d = gradPredictionLoss2d.dup('c').reshape(mb, b, h, w, c).permute(0,1,4,2,3).dup('c');
        epsClassPredictions.assign(gradPredictionLoss5d);


        //Calculate gradient component from position (x,y) loss - dL_position/dx and dL_position/dy
        INDArray gradXYCenter2d = layerConf().getLossPositionScale().computeGradient(labelXYCenter2d, predictedXYCenter2d, identity, mask1_ij_obj_2d);
        gradXYCenter2d.muli(lambdaCoord);
        INDArray gradXYCenter5d = gradXYCenter2d.dup('c')
                .reshape('c', mb, b, h, w, 2)
                .permute(0,1,4,2,3);   //From: [mb, B, H, W, 2] to [mb, B, 2, H, W]
        gradXYCenter5d = new ActivationSigmoid().backprop(preSigmoidPredictedXYCenterGrid.dup(), gradXYCenter5d).getFirst();
        epsXY.assign(gradXYCenter5d);

        //Calculate gradient component from width/height (w,h) loss - dL_size/dw and dL_size/dw
        //Note that loss function gets sqrt(w) and sqrt(h)
        //gradWHSqrt2d = dL/dsqrt(w) and dL/dsqrt(h)
        INDArray gradWHSqrt2d = layerConf().getLossPositionScale().computeGradient(labelWHSqrt2d, predictedWHSqrt2d, identity, mask1_ij_obj_2d);   //Shape: [mb*b*h*w, 2]
            //dL/dw = dL/dsqrtw * dsqrtw / dw = dL/dsqrtw * 0.5 / sqrt(w)
        INDArray gradWH2d = gradWHSqrt2d.muli(0.5).divi(predictedWHSqrt2d);  //dL/dw and dL/dh, w = pw * exp(tw)
            //dL/dinWH = dL/dw * dw/dInWH = dL/dw * pw * exp(tw)
        INDArray gradWH5d = gradWH2d.dup('c').reshape(mb, b, h, w, 2).permute(0,1,4,2,3);   //To: [mb, b, 2, h, w]
        gradWH5d.muli(predictedWH);
        gradWH5d.muli(lambdaCoord);
        epsWH.assign(gradWH5d);


        //Calculate gradient component from confidence loss... 2 parts (object present, no object present)
        INDArray gradConfidence2dA = lossConfidence.computeGradient(labelConfidence2d, predictedConfidence2d, identity, mask1_ij_obj_2d);
        INDArray gradConfidence2dB = lossConfidence.computeGradient(labelConfidence2d, predictedConfidence2d, identity, mask1_ij_noobj_2d);


        INDArray dLc_dC_2d = gradConfidence2dA.addi(gradConfidence2dB.muli(lambdaNoObj));  //dL/dC; C = sigmoid(tc)
        INDArray dLc_dzc_2d = new ActivationSigmoid().backprop( predictedConfidence2dPreSigmoid, dLc_dC_2d).getFirst();
        //Calculate dL/dtc
        INDArray epsConfidence4d = dLc_dzc_2d.dup('c').reshape('c', mb, b, h, w);   //[mb*b*h*w, 2] to [mb, b, h, w]
        epsC.assign(epsConfidence4d);





        //Note that we ALSO have components to x,y,w,h  from confidence loss (via IOU, which depends on all of these values)
        //that is: dLc/dx, dLc/dy, dLc/dw, dLc/dh
        //For any value v, d(I/U)/dv = (U * dI/dv + I * dU/dv) / U^2

        //Confidence loss: sum squared errors + masking.
        //C == IOU when label present

        //Lc = 1^(obj)*(iou - predicted)^2 + lambdaNoObj * 1^(noobj) * (iou - predicted)^2 -> dLc/diou = 2*1^(obj)*(iou-predicted) + 2 * lambdaNoObj * 1^(noobj) * (iou-predicted) = 2*(iou-predicted) * (1^(obj) + lambdaNoObj * 1^(noobj))
        INDArray twoIOUSubPredicted = iou.subi(predictedConfidence).muli(2.0);  //Shape: [mb, b, h, w]. Note that when an object is present, IOU and confidence are the same. In-place to avoid copy op (iou no longer needed)
        INDArray dLc_dIOU = twoIOUSubPredicted.muli(mask1_ij_obj.add(mask1_ij_noobj.muli(lambdaNoObj)));    //Modify mask1_ij_noobj - avoid extra temp array allocatino


        INDArray dLc_dxy = Nd4j.createUninitialized(iouRet.dIOU_dxy.shape(), iouRet.dIOU_dxy.ordering());
        Broadcast.mul(iouRet.dIOU_dxy, dLc_dIOU, dLc_dxy, 0, 1, 3, 4);    //[mb, b, h, w] x [mb, b, 2, h, w]

        INDArray dLc_dwh = Nd4j.createUninitialized(iouRet.dIOU_dwh.shape(), iouRet.dIOU_dwh.ordering());
        Broadcast.mul(iouRet.dIOU_dwh, dLc_dIOU, dLc_dwh, 0, 1, 3, 4);    //[mb, b, h, w] x [mb, b, 2, h, w]


        //Backprop through the wh and xy activation functions...
        //dL/dw and dL/dh, w = pw * exp(tw), //dL/dinWH = dL/dw * dw/dInWH = dL/dw * pw * exp(in_w)
        //as w = pw * exp(in_w) and dw/din_w = w
        INDArray dLc_din_wh = dLc_dwh.muli(predictedWH);
        INDArray dLc_din_xy = new ActivationSigmoid().backprop(preSigmoidPredictedXYCenterGrid, dLc_dxy).getFirst();    //Shape: same as subset of input... [mb, b, 2, h, w]

        //Finally, apply masks: dLc_dwh and dLc_dxy should be 0 if no object is present in that box
        //Apply mask 1^obj_ij with shape [mb, b, h, w]
        Broadcast.mul(dLc_din_wh, mask1_ij_obj, dLc_din_wh, 0, 1, 3, 4);
        Broadcast.mul(dLc_din_xy, mask1_ij_obj, dLc_din_xy, 0, 1, 3, 4);


        epsWH.addi(dLc_din_wh);
        epsXY.addi(dLc_din_xy);

        return epsOut;
    }

    @Override
    public Activations activate(boolean training) {
        INDArray input = this.input.get(0);
        //Essentially: just apply activation functions...

        int mb = input.size(0);
        int h = input.size(2);
        int w = input.size(3);
        int b = layerConf().getBoundingBoxes().size(0);
        int c = (input.size(1)/b)-5;  //input.size(1) == b * (5 + C) -> C = (input.size(1)/b) - 5

        INDArray output = Nd4j.create(input.shape(), 'c');
        INDArray output5 = output.reshape('c', mb, b, 5+c, h, w);
        INDArray output4 = output;  //output.get(all(), interval(0,5*b), all(), all());
        INDArray input4 = input.dup('c');    //input.get(all(), interval(0,5*b), all(), all()).dup('c');
        INDArray input5 = input4.reshape('c', mb, b, 5+c, h, w);

        //X/Y center in grid: sigmoid
        INDArray predictedXYCenterGrid = input5.get(all(), all(), interval(0,2), all(), all());
        Transforms.sigmoid(predictedXYCenterGrid, false);

        //width/height: prior * exp(input)
        INDArray predictedWHPreExp = input5.get(all(), all(), interval(2,4), all(), all());
        INDArray predictedWH = Transforms.exp(predictedWHPreExp, false);
        Broadcast.mul(predictedWH, layerConf().getBoundingBoxes(), predictedWH, 1, 2);  //Box priors: [b, 2]; predictedWH: [mb, b, 2, h, w]

        //Confidence - sigmoid
        INDArray predictedConf = input5.get(all(), all(), point(4), all(), all());   //Shape: [mb, B, H, W]
        Transforms.sigmoid(predictedConf, false);

        output4.assign(input4);

        //Softmax
        //TODO OPTIMIZE?
        INDArray inputClassesPreSoftmax = input5.get(all(), all(), interval(5, 5+c), all(), all());   //Shape: [minibatch, C, H, W]
        INDArray classPredictionsPreSoftmax2d = inputClassesPreSoftmax.permute(0,1,3,4,2) //[minibatch, b, c, h, w] To [mb, b, h, w, c]
                .dup('c').reshape('c', new int[]{mb*b*h*w, c});
        Transforms.softmax(classPredictionsPreSoftmax2d, false);
        INDArray postSoftmax5d = classPredictionsPreSoftmax2d.reshape('c', mb, b, h, w, c ).permute(0, 1, 4, 2, 3);

        INDArray outputClasses = output5.get(all(), all(), interval(5, 5+c), all(), all());   //Shape: [minibatch, C, H, W]
        outputClasses.assign(postSoftmax5d);

        return ActivationsFactory.getInstance().create(output);
    }

    @Override
    public Layer clone() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public double computeScore(double fullNetworkL1, double fullNetworkL2, boolean training) {
        this.fullNetworkL1 = fullNetworkL1;
        this.fullNetworkL2 = fullNetworkL2;

        //TODO optimize?
        computeBackpropGradientAndScore();
        return score();
    }

    @Override
    public void init() {
        //No op
    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        //No op
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        //No op
    }

    @Override
    public void addListeners(IterationListener... listener) {
        //No op
    }

    @Override
    public Collection<IterationListener> getListeners() {
        return Collections.emptyList();
    }

    @Override
    public void setLabels(INDArray labels, INDArray labelMask) {
        if(labelMask != null){
            throw new IllegalStateException("Label masks are not yet supported by Yolo2OutputLayer");
        }
        this.labels = labels;
    }

    @Override
    public INDArray getLabelMask() {
        return null;
    }

    @Override
    public double score(){
        return score;
    }

    /**
     * Calculate IOU(truth, predicted) and gradients. Returns 5d arrays [mb, b, 2, H, W]
     * ***NOTE: All labels - and predicted values - are in terms of grid units - 0 to 12 usually, with default config ***
     *
     * @param labelTL   4d [mb, 2, H, W], label top/left (x,y) in terms of grid boxes
     * @param labelBR   4d [mb, 2, H, W], label bottom/right (x,y) in terms of grid boxes
     * @param predictedWH 5d [mb, b, 2, H, W] - predicted H/W in terms of number of grid boxes.
     * @param predictedXYinGridBox 5d [mb, b, 2, H, W] - predicted X/Y in terms of number of grid boxes. Values 0 to 1, center box value being 0.5
     * @param objectPresentMask 3d [mb, H, W] - mask array, for objects present (1) or not (0) in grid cell
     * @return IOU and gradients
     */
    private static IOURet calculateIOULabelPredicted(INDArray labelTL, INDArray labelBR, INDArray predictedWH, INDArray predictedXYinGridBox, INDArray objectPresentMask){
        int mb = labelTL.size(0);
        int h = labelTL.size(2);
        int w = labelTL.size(3);
        int b = predictedWH.size(1);

        INDArray labelWH = labelBR.sub(labelTL);                //4d [mb, 2, H, W], label W/H in terms of number of grid boxes

        int gridW = labelTL.size(2);
        int gridH = labelTL.size(3);
        //Add grid positions to the predicted XY values (to get predicted XY in terms of grid cell units in image,
        // from (0 to 1 in grid cell) format)
        INDArray linspaceX = Nd4j.linspace(0, gridW-1, gridW);
        INDArray linspaceY = Nd4j.linspace(0, gridH-1, gridH);
        INDArray grid = Nd4j.createUninitialized(new int[]{2, gridH, gridW}, 'c');
        INDArray gridX = grid.get(point(0), all(), all());
        INDArray gridY = grid.get(point(1), all(), all());
        Broadcast.copy(gridX, linspaceX, gridX, 1);
        Broadcast.copy(gridY, linspaceY, gridY, 0);

        //Calculate X/Y position overall (in grid box units) from "position in current grid box" format
        INDArray predictedXY = Nd4j.createUninitialized(predictedXYinGridBox.shape(), predictedXYinGridBox.ordering());
        Broadcast.add(predictedXYinGridBox, grid, predictedXY, 2,3,4); // [2, H, W] to [mb, b, 2, H, W]


        INDArray halfWH = predictedWH.mul(0.5);
        INDArray predictedTL_XY = halfWH.rsub(predictedXY);     //xy - 0.5 * wh
        INDArray predictedBR_XY = halfWH.add(predictedXY);      //xy + 0.5 * wh

        INDArray maxTL = Nd4j.createUninitialized(predictedTL_XY.shape(), predictedTL_XY.ordering());   //Shape: [mb, b, 2, H, W]
        Broadcast.max(predictedTL_XY, labelTL, maxTL, 0, 2, 3, 4);
        INDArray minBR = Nd4j.createUninitialized(predictedBR_XY.shape(), predictedBR_XY.ordering());
        Broadcast.min(predictedBR_XY, labelBR, minBR, 0, 2, 3, 4);

        INDArray diff = minBR.sub(maxTL);
        INDArray intersectionArea = diff.prod(2);   //[mb, b, 2, H, W] to [mb, b, H, W]
        Broadcast.mul(intersectionArea, objectPresentMask, intersectionArea, 0, 2, 3);

        //Need to mask the calculated intersection values, to avoid returning non-zero values when intersection is actually 0
        //No intersection if: xP + wP/2 < xL - wL/2 i.e., BR_xPred < TL_xLab   OR  TL_xPred > BR_xLab (similar for Y axis)
        //Here, 1 if intersection exists, 0 otherwise. This is doing x/w and y/h simultaneously
        INDArray noIntMask1 = Nd4j.createUninitialized(maxTL.shape(), maxTL.ordering());
        INDArray noIntMask2 = Nd4j.createUninitialized(maxTL.shape(), maxTL.ordering());
        //Does both x and y on different dims
        Broadcast.lt(predictedBR_XY, labelTL, noIntMask1, 0, 2, 3, 4);  //Predicted BR < label TL
        Broadcast.gt(predictedTL_XY, labelBR, noIntMask2, 0, 2, 3, 4);  //predicted TL > label BR

        noIntMask1 = Transforms.or(noIntMask1.get(all(), all(), point(0), all(), all()), noIntMask1.get(all(), all(), point(1), all(), all()) );    //Shape: [mb, b, H, W]. Values 1 if no intersection
        noIntMask2 = Transforms.or(noIntMask2.get(all(), all(), point(0), all(), all()), noIntMask2.get(all(), all(), point(1), all(), all()) );
        INDArray noIntMask = Transforms.or(noIntMask1, noIntMask2 );

        INDArray intMask = Nd4j.getExecutioner().execAndReturn(new Not(noIntMask, noIntMask, 0.0)); //Values 0 if no intersection
        Broadcast.mul(intMask, objectPresentMask, intMask, 0, 2, 3);

        //Mask the intersection area: should be 0 if no intersection
        intersectionArea.muli(intMask);


        //Next, union area is simple: U = A1 + A2 - intersection
        INDArray areaPredicted = predictedWH.prod(2);   //[mb, b, 2, H, W] to [mb, b, H, W]
        Broadcast.mul(areaPredicted, objectPresentMask, areaPredicted, 0,2,3);
        INDArray areaLabel = labelWH.prod(1);           //[mb, 2, H, W] to [mb, H, W]

        INDArray unionArea = Broadcast.add(areaPredicted, areaLabel, areaPredicted.dup(), 0, 2, 3);
        unionArea.subi(intersectionArea);
        unionArea.muli(intMask);

        INDArray iou = intersectionArea.div(unionArea);
        BooleanIndexing.replaceWhere(iou, 0.0, Conditions.isNan()); //0/0 -> NaN -> 0

        //Apply the "object present" mask (of shape [mb, h, w]) - this ensures IOU is 0 if no object is present
        Broadcast.mul(iou, objectPresentMask, iou, 0, 2, 3);

        //Finally, calculate derivatives:
        INDArray maskMaxTL = Nd4j.createUninitialized(maxTL.shape(), maxTL.ordering());    //1 if predicted Top/Left is max, 0 otherwise
        Broadcast.gt(predictedTL_XY, labelTL, maskMaxTL, 0, 2, 3, 4);   // z = x > y

        INDArray maskMinBR = Nd4j.createUninitialized(maxTL.shape(), maxTL.ordering());    //1 if predicted Top/Left is max, 0 otherwise
        Broadcast.lt(predictedBR_XY, labelBR, maskMinBR, 0, 2, 3, 4);   // z = x < y

        //dI/dx = lambda * (1^(min(x1+w1/2) - 1^(max(x1-w1/2))
        //dI/dy = omega * (1^(min(y1+h1/2) - 1^(max(y1-h1/2))
        //omega = min(x1+w1/2,x2+w2/2) - max(x1-w1/2,x2+w2/2)       i.e., from diff = minBR.sub(maxTL), which has shape [mb, b, 2, h, w]
        //lambda = min(y1+h1/2,y2+h2/2) - max(y1-h1/2,y2+h2/2)
        INDArray dI_dxy = maskMinBR.sub(maskMaxTL);              //Shape: [mb, b, 2, h, w]
        INDArray dI_dwh = maskMinBR.add(maskMaxTL).muli(0.5);    //Shape: [mb, b, 2, h, w]

        dI_dxy.get(all(), all(), point(0), all(), all()).muli(diff.get(all(), all(), point(1), all(), all()));
        dI_dxy.get(all(), all(), point(1), all(), all()).muli(diff.get(all(), all(), point(0), all(), all()));

        dI_dwh.get(all(), all(), point(0), all(), all()).muli(diff.get(all(), all(), point(1), all(), all()));
        dI_dwh.get(all(), all(), point(1), all(), all()).muli(diff.get(all(), all(), point(0), all(), all()));

        //And derivatives WRT IOU:
        INDArray uPlusI = unionArea.add(intersectionArea);
        INDArray u2 = unionArea.mul(unionArea);
        INDArray uPlusIDivU2 = uPlusI.div(u2);   //Shape: [mb, b, h, w]
        BooleanIndexing.replaceWhere(uPlusIDivU2, 0.0, Conditions.isNan());     //Handle 0/0

        INDArray dIOU_dxy = Nd4j.createUninitialized(new int[]{mb, b, 2, h, w}, 'c');
        Broadcast.mul(dI_dxy, uPlusIDivU2, dIOU_dxy, 0, 1, 3, 4);   //[mb, b, h, w] x [mb, b, 2, h, w]

        INDArray predictedHW = Nd4j.createUninitialized(new int[]{mb, b, 2, h, w}, predictedWH.ordering());
        //Next 2 lines: permuting the order... WH to HW along dimension 2
        predictedHW.get(all(), all(), point(0), all(), all()).assign(predictedWH.get(all(), all(), point(1), all(), all()));
        predictedHW.get(all(), all(), point(1), all(), all()).assign(predictedWH.get(all(), all(), point(0), all(), all()));

        INDArray Ihw = Nd4j.createUninitialized(predictedHW.shape(), predictedHW.ordering());
        Broadcast.mul(predictedHW, intersectionArea, Ihw, 0, 1, 3, 4 );    //Predicted_wh: [mb, b, 2, h, w]; intersection: [mb, b, h, w]

        INDArray dIOU_dwh = Nd4j.createUninitialized(new int[]{mb, b, 2, h, w}, 'c');
        Broadcast.mul(dI_dwh, uPlusI, dIOU_dwh, 0, 1, 3, 4);
        dIOU_dwh.subi(Ihw);
        Broadcast.div(dIOU_dwh, u2, dIOU_dwh, 0, 1, 3, 4);
        BooleanIndexing.replaceWhere(dIOU_dwh, 0.0, Conditions.isNan());     //Handle division by 0 (due to masking, etc)

        return new IOURet(iou, dIOU_dxy, dIOU_dwh);
    }


    @AllArgsConstructor
    @Data
    private static class IOURet {
        private INDArray iou;
        private INDArray dIOU_dxy;
        private INDArray dIOU_dwh;

    }

    @Override
    public void computeGradientAndScore(){

        //TODO
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void fit(Activations data) {
        throw new UnsupportedOperationException("Cannot fit Yolo2OutputLayer from activations only (without labels)");
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(), score());
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return null;
    }

    @Override
    public INDArray computeScoreForExamples(double fullNetworkL1, double fullNetworkL2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fit(DataSetIterator iter) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void fit(INDArray examples, INDArray labels) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void fit(DataSet data) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public boolean isPretrainLayer() {
        return false;
    }

    @Override
    public void clearNoiseWeightParams() {
        //No op
    }

    /**
     * Given the network output and a detection threshold (in range 0 to 1) determine the objects detected by
     * the network.<br>
     * Supports minibatches - the returned {@link DetectedObject} instances have an example number index.<br>
     *
     * Note that the dimensions are grid cell units - for example, with 416x416 input, 32x downsampling by the network
     * (before getting to the Yolo2OutputLayer) we have 13x13 grid cells (each corresponding to 32 pixels in the input
     * image). Thus, a centerX of 5.5 would be xPixels=5.5x32 = 176 pixels from left. Widths and heights are similar:
     * in this example, a with of 13 would be the entire image (416 pixels), and a height of 6.5 would be 6.5/13 = 0.5
     * of the image (208 pixels).
     *
     * @param networkOutput 4d activations out of the network
     * @param threshold Detection threshold, in range 0 (most strict) to 1 (least strict). Objects are returned where
     *                  predicted confidence is >= threshold
     * @return List of detected objects
     */
    public List<DetectedObject> getPredictedObjects(INDArray networkOutput, double threshold){
        if(networkOutput.rank() != 4){
            throw new IllegalStateException("Invalid network output activations array: should be rank 4. Got array "
                    + "with shape " + Arrays.toString(networkOutput.shape()));
        }
        if(threshold < 0.0 || threshold > 1.0){
            throw new IllegalStateException("Invalid threshold: must be in range [0,1]. Got: " + threshold);
        }

        //Activations format: [mb, 5b+c, h, w]
        int mb = networkOutput.size(0);
        int h = networkOutput.size(2);
        int w = networkOutput.size(3);
        int b = layerConf().getBoundingBoxes().size(0);
        int c = (networkOutput.size(1)/b)-5;  //input.size(1) == b * (5 + C) -> C = (input.size(1)/b) - 5

        //Reshape from [minibatch, B*(5+C), H, W] to [minibatch, B, 5+C, H, W] to [minibatch, B, 5, H, W]
        INDArray output5 = networkOutput.dup('c').reshape(mb, b, 5+c, h, w);
        INDArray predictedConfidence = output5.get(all(), all(), point(4), all(), all());    //Shape: [mb, B, H, W]
        INDArray softmax = output5.get(all(), all(), interval(5, 5+c), all(), all());

        List<DetectedObject> out = new ArrayList<>();
        for( int i=0; i<mb; i++ ){
            for( int x=0; x<w; x++ ){
                for( int y=0; y<h; y++ ){
                    for( int box=0; box<b; box++ ){
                        double conf = predictedConfidence.getDouble(i, box, y, x);
                        if(conf < threshold){
                            continue;
                        }

                        double px = output5.getDouble(i, box, 0, y, x); //Originally: in 0 to 1 in grid cell
                        double py = output5.getDouble(i, box, 1, y, x); //Originally: in 0 to 1 in grid cell
                        double pw = output5.getDouble(i, box, 2, y, x); //In grid units (for example, 0 to 13)
                        double ph = output5.getDouble(i, box, 3, y, x); //In grid units (for example, 0 to 13)

                        //Convert the "position in grid cell" to "position in image (in grid cell units)"
                        px += x;
                        py += y;


                        INDArray sm;
                        try (MemoryWorkspace wsO = Nd4j.getMemoryManager().scopeOutOfWorkspaces()) {
                            sm = softmax.get(point(i), point(box), all(), point(y), point(x)).dup();
                        }

                        out.add(new DetectedObject(i, px, py, pw, ph, sm, conf));
                    }
                }
            }
        }

        return out;
    }

    /**
     * Get the confidence matrix (confidence for all x/y positions) for the specified bounding box, from the network
     * output activations array
     *
     * @param networkOutput Network output activations
     * @param example       Example number, in minibatch
     * @param bbNumber      Bounding box number
     * @return Confidence matrix
     */
    public INDArray getConfidenceMatrix(INDArray networkOutput, int example, int bbNumber){

        //Input format: [minibatch, 5B+C, H, W], with order [x,y,w,h,c]
        //Therefore: confidences are at depths 4 + bbNumber * 5

        INDArray conf = networkOutput.get(point(example), point(4+bbNumber*5), all(), all());
        return conf;
    }

    /**
     * Get the probability matrix (probability of the specified class, assuming an object is present, for all x/y
     * positions), from the network output activations array
     *
     * @param networkOutput Network output activations
     * @param example       Example number, in minibatch
     * @param classNumber   Class number
     * @return Confidence matrix
     */
    public INDArray getProbabilityMatrix(INDArray networkOutput, int example, int classNumber){
        //Input format: [minibatch, 5B+C, H, W], with order [x,y,w,h,c]
        //Therefore: probabilities for class I is at depths 5B + classNumber

        int bbs = layerConf().getBoundingBoxes().size(0);
        INDArray conf = networkOutput.get(point(example), point(5*bbs + classNumber), all(), all());
        return conf;
    }
}