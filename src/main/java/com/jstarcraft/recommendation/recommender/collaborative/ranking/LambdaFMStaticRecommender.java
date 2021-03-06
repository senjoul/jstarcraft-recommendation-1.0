package com.jstarcraft.recommendation.recommender.collaborative.ranking;

import java.util.Arrays;
import java.util.Comparator;

import com.jstarcraft.ai.math.structure.DefaultScalar;
import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.matrix.MatrixScalar;
import com.jstarcraft.ai.math.structure.vector.DenseVector;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.utility.LogisticUtility;
import com.jstarcraft.recommendation.utility.SampleUtility;

/**
 * 
 * Lambda FM推荐器
 * 
 * <pre>
 * LambdaFM: Learning Optimal Ranking with Factorization Machines Using Lambda Surrogates
 * </pre>
 * 
 * @author Birdy
 *
 */
public class LambdaFMStaticRecommender extends LambdaFMRecommender {

	// Static
	private float staticRho;
	protected DenseVector itemProbabilities;

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);
		staticRho = configuration.getFloat("rec.item.distribution.parameter");
		// calculate popularity
		Integer[] orderItems = new Integer[numberOfItems];
		for (int itemIndex = 0; itemIndex < numberOfItems; itemIndex++) {
			orderItems[itemIndex] = itemIndex;
		}
		Arrays.sort(orderItems, new Comparator<Integer>() {
			@Override
			public int compare(Integer leftItemIndex, Integer rightItemIndex) {
				return (trainMatrix.getColumnScope(leftItemIndex) > trainMatrix.getColumnScope(rightItemIndex) ? -1 : (trainMatrix.getColumnScope(leftItemIndex) < trainMatrix.getColumnScope(rightItemIndex) ? 1 : 0));
			}
		});
		Integer[] itemOrders = new Integer[numberOfItems];
		for (int index = 0; index < numberOfItems; index++) {
			int itemIndex = orderItems[index];
			itemOrders[itemIndex] = index;
		}
		DefaultScalar sum = DefaultScalar.getInstance();
		sum.setValue(0F);
		itemProbabilities = DenseVector.valueOf(numberOfItems);
		itemProbabilities.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			int index = scalar.getIndex();
			float value = (float) Math.exp(-(itemOrders[index] + 1) / (numberOfItems * staticRho));
			sum.shiftValue(value);
			scalar.setValue(sum.getValue());
		});

		for (MatrixScalar term : trainMatrix) {
			term.setValue(itemProbabilities.getValue(term.getColumn()));
		}
	}

	@Override
	protected float getGradientValue(DefaultScalar scalar, int[] dataPaginations, int[] dataPositions) {
		int userIndex;
		while (true) {
			userIndex = RandomUtility.randomInteger(numberOfUsers);
			SparseVector userVector = trainMatrix.getRowVector(userIndex);
			if (userVector.getElementSize() == 0 || userVector.getElementSize() == numberOfItems) {
				continue;
			}

			int from = dataPaginations[userIndex], to = dataPaginations[userIndex + 1];
			int positivePosition = dataPositions[RandomUtility.randomInteger(from, to)];
			for (int index = 0; index < negativeKeys.length; index++) {
				positiveKeys[index] = marker.getDiscreteFeature(index, positivePosition);
			}

			// TODO 注意,此处为了故意制造负面特征.
			int negativeItemIndex = -1;
			while (negativeItemIndex == -1) {
				int position = SampleUtility.binarySearch(userVector, 0, userVector.getElementSize() - 1, RandomUtility.randomFloat(itemProbabilities.getValue(itemProbabilities.getElementSize() - 1)));
				int low;
				int high;
				if (position == -1) {
					low = userVector.getIndex(userVector.getElementSize() - 1);
					high = itemProbabilities.getElementSize() - 1;
				} else if (position == 0) {
					low = 0;
					high = userVector.getIndex(position);
				} else {
					low = userVector.getIndex(position - 1);
					high = userVector.getIndex(position);
				}
				negativeItemIndex = SampleUtility.binarySearch(itemProbabilities, low, high, RandomUtility.randomFloat(itemProbabilities.getValue(high)));
			}
			int negativePosition = dataPositions[RandomUtility.randomInteger(from, to)];
			for (int index = 0; index < negativeKeys.length; index++) {
				negativeKeys[index] = marker.getDiscreteFeature(index, negativePosition);
			}
			negativeKeys[itemDimension] = negativeItemIndex;
			break;
		}

		positiveVector = getFeatureVector(positiveKeys);
		negativeVector = getFeatureVector(negativeKeys);

		float positiveScore = predict(scalar, positiveVector);
		float negativeScore = predict(scalar, negativeVector);

		float error = positiveScore - negativeScore;

		// 由于pij_real默认为1,所以简化了loss的计算.
		// loss += -pij_real * Math.log(pij) - (1 - pij_real) *
		// Math.log(1 - pij);
		totalLoss += (float) -Math.log(LogisticUtility.getValue(error));
		float gradient = calaculateGradientValue(lossType, error);
		return gradient;
	}

}
