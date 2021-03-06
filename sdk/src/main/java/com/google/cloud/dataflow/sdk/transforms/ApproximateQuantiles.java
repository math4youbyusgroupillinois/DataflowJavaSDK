/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.transforms;

import com.google.cloud.dataflow.sdk.coders.BigEndianIntegerCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.coders.CoderRegistry;
import com.google.cloud.dataflow.sdk.coders.CustomCoder;
import com.google.cloud.dataflow.sdk.coders.ListCoder;
import com.google.cloud.dataflow.sdk.transforms.Combine.AccumulatingCombineFn;
import com.google.cloud.dataflow.sdk.util.common.ElementByteSizeObserver;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.UnmodifiableIterator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * {@code PTransform}s for getting an idea of a {@code PCollection}'s
 * data distribution using approximate {@code N}-tiles, either
 * globally or per-key.
 */
public class ApproximateQuantiles {

  /**
   * Returns a {@code PTransform} that takes a {@code PCollection<T>}
   * and returns a {@code PCollection<List<T>>} whose single value is a
   * {@code List} of the approximate {@code N}-tiles of the elements
   * of the input {@code PCollection}.  This gives an idea of the
   * distribution of the input elements.
   *
   * <p> The computed {@code List} is of size {@code numQuantiles},
   * and contains the input elements' minimum value,
   * {@code numQuantiles-2} intermediate values, and maximum value, in
   * sorted order, using the given {@code Comparator} to order values.
   * To compute traditional {@code N}-tiles, one should use
   * {@code ApproximateQuantiles.globally(compareFn, N+1)}.
   *
   * <p> If there are fewer input elements than {@code numQuantiles},
   * then the result {@code List} will contain all the input elements,
   * in sorted order.
   *
   * <p> The argument {@code Comparator} must be {@code Serializable}.
   *
   * <p> Example of use:
   * <pre> {@code
   * PCollection<String> pc = ...;
   * PCollection<List<String>> quantiles =
   *     pc.apply(ApproximateQuantiles.globally(stringCompareFn, 11));
   * } </pre>
   *
   * @param <T> the type of the elements in the input {@code PCollection}
   * @param numQuantiles the number of elements in the resulting
   *        quantile values {@code List}
   * @param compareFn the function to use to order the elements
   */
  public static <T, C extends Comparator<T> & Serializable>
  PTransform<PCollection<T>, PCollection<List<T>>> globally(
      int numQuantiles, C compareFn) {
    return Combine.globally(
        ApproximateQuantilesCombineFn.create(numQuantiles, compareFn));
  }

  /**
   * Like {@link #globally(int, Comparator)}, but sorts using the
   * elements' natural ordering.
   *
   * @param <T> the type of the elements in the input {@code PCollection}
   * @param numQuantiles the number of elements in the resulting
   *        quantile values {@code List}
   */
  public static <T extends Comparable<T>>
      PTransform<PCollection<T>, PCollection<List<T>>> globally(int numQuantiles) {
    return Combine.globally(
        ApproximateQuantilesCombineFn.<T>create(numQuantiles));
  }

  /**
   * Returns a {@code PTransform} that takes a
   * {@code PCollection<KV<K, V>>} and returns a
   * {@code PCollection<KV<K, List<V>>>} that contains an output
   * element mapping each distinct key in the input
   * {@code PCollection} to a {@code List} of the approximate
   * {@code N}-tiles of the values associated with that key in the
   * input {@code PCollection}.  This gives an idea of the
   * distribution of the input values for each key.
   *
   * <p> Each of the computed {@code List}s is of size {@code numQuantiles},
   * and contains the input values' minimum value,
   * {@code numQuantiles-2} intermediate values, and maximum value, in
   * sorted order, using the given {@code Comparator} to order values.
   * To compute traditional {@code N}-tiles, one should use
   * {@code ApproximateQuantiles.perKey(compareFn, N+1)}.
   *
   * <p> If a key has fewer than {@code numQuantiles} values
   * associated with it, then that key's output {@code List} will
   * contain all the key's input values, in sorted order.
   *
   * <p> The argument {@code Comparator} must be {@code Serializable}.
   *
   * <p> Example of use:
   * <pre> {@code
   * PCollection<KV<Integer, String>> pc = ...;
   * PCollection<KV<Integer, List<String>>> quantilesPerKey =
   *     pc.apply(ApproximateQuantiles.<Integer, String>perKey(stringCompareFn, 11));
   * } </pre>
   *
   * <p> See {@link Combine.PerKey} for how this affects timestamps and windowing.
   *
   * @param <K> the type of the keys in the input and output
   *        {@code PCollection}s
   * @param <V> the type of the values in the input {@code PCollection}
   * @param numQuantiles the number of elements in the resulting
   *        quantile values {@code List}
   * @param compareFn the function to use to order the elements
   */
  public static <K, V, C extends Comparator<V> & Serializable>
      PTransform<PCollection<KV<K, V>>, PCollection<KV<K, List<V>>>>
      perKey(int numQuantiles, C compareFn) {
    return Combine.perKey(
        ApproximateQuantilesCombineFn.create(numQuantiles, compareFn)
        .<K>asKeyedFn());
  }

  /**
   * Like {@link #perKey(int, Comparator)}, but sorts
   * values using the their natural ordering.
   *
   * @param <K> the type of the keys in the input and output
   *        {@code PCollection}s
   * @param <V> the type of the values in the input {@code PCollection}
   * @param numQuantiles the number of elements in the resulting
   *        quantile values {@code List}
   */
  public static <K, V extends Comparable<V>>
      PTransform<PCollection<KV<K, V>>, PCollection<KV<K, List<V>>>>
      perKey(int numQuantiles) {
    return Combine.perKey(
        ApproximateQuantilesCombineFn.<V>create(numQuantiles)
        .<K>asKeyedFn());
  }


  /////////////////////////////////////////////////////////////////////////////

  /**
   * The {@code ApproximateQuantilesCombineFn} combiner gives an idea
   * of the distribution of a collection of values using approximate
   * {@code N}-tiles.  The output of this combiner is a {@code List}
   * of size {@code numQuantiles}, containing the input values'
   * minimum value, {@code numQuantiles-2} intermediate values, and
   * maximum value, in sorted order, so for traditional
   * {@code N}-tiles, one should use
   * {@code ApproximateQuantilesCombineFn#create(N+1)}.
   *
   * <p> If there are fewer values to combine than
   * {@code numQuantiles}, then the result {@code List} will contain all the
   * values being combined, in sorted order.
   *
   * <P> Values are ordered using either a specified
   * {@code Comparator} or the values' natural ordering.
   *
   * <p> To evaluate the quantiles we use the "New Algorithm" described here:
   * <pre>
   *   [MRL98] Manku, Rajagopalan & Lindsay, "Approximate Medians and other
   *   Quantiles in One Pass and with Limited Memory", Proc. 1998 ACM
   *   SIGMOD, Vol 27, No 2, p 426-435, June 1998.
   *   http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.6.6513&rep=rep1&type=pdf
   * </pre>
   *
   * <P> The default error bound is {@code 1 / N}, though in practice
   * the accuracy tends to be much better.  <p> See
   * {@link #create(int, Comparator, long, double)} for
   * more information about the meaning of {@code epsilon}, and
   * {@link #withEpsilon} for a convenient way to adjust it.
   *
   * @param <T> the type of the values being combined
   */
  @SuppressWarnings("serial")
  public static class ApproximateQuantilesCombineFn
      <T, C extends Comparator<T> & Serializable>
      extends AccumulatingCombineFn
      <T, ApproximateQuantilesCombineFn<T, C>.QuantileState, List<T>> {

    /**
     * The cost (in time and space) to compute quantiles to a given
     * accuracy is a function of the total number of elements in the
     * data set.  If an estimate is not known or specified, we use
     * this as an upper bound.  If this is too low, errors may exceed
     * the requested tolerance; if too high, efficiency may be
     * non-optimal.  The impact is logarithmic with respect to this
     * value, so this default should be fine for most uses.
     */
    public static final long DEFAULT_MAX_NUM_ELEMENTS = (long) 1e9;

    /** The comparison function to use. */
    private final C compareFn;

    /**
     * Number of quantiles to produce.  The size of the final output
     * list, including the minimum and maximum, is numQuantiles.
     */
    private final int numQuantiles;

    /** The size of the buffers, corresponding to k in the referenced paper. */
    private final int bufferSize;

    /**  The number of buffers, corresponding to b in the referenced paper. */
    private final int numBuffers;

    private final double epsilon;
    private final long maxNumElements;

    /**
     * Used to alternate between biasing up and down in the even weight collapse
     * operation.
     */
    private int offsetJitter = 0;

    /**
     * Returns an approximate quantiles combiner with the given
     * {@code compareFn} and desired number of quantiles.  A total of
     * {@code numQuantiles} elements will appear in the output list,
     * including the minimum and maximum.
     *
     * <p> The {@code Comparator} must be {@code Serializable}.
     *
     * <p> The default error bound is {@code 1 / numQuantiles} which
     * holds as long as the number of elements is less than
     * {@link #DEFAULT_MAX_NUM_ELEMENTS}.
     */
    public static <T, C extends Comparator<T> & Serializable>
    ApproximateQuantilesCombineFn<T, C> create(
        int numQuantiles, C compareFn) {
      return create(numQuantiles, compareFn,
                    DEFAULT_MAX_NUM_ELEMENTS, 1.0 / numQuantiles);
    }

    /**
     * Like {@link #create(int, Comparator)}, but sorts
     * values using their natural ordering.
     */
    public static <T extends Comparable<T>>
    ApproximateQuantilesCombineFn<T, Top.Largest<T>> create(int numQuantiles) {
      return create(numQuantiles, new Top.Largest<T>());
    }

    /**
     * Returns an {@code ApproximateQuantilesCombineFn} that's like
     * this one except that it uses the specified {@code epsilon}
     * value.  Does not modify this combiner.
     *
     * <p> See {@link #create(int, Comparator, long,
     * double)} for more information about the meaning of
     * {@code epsilon}.
     */
    public ApproximateQuantilesCombineFn<T, C> withEpsilon(double epsilon) {
      return create(numQuantiles, compareFn, maxNumElements, epsilon);
    }

    /**
     * Returns an {@code ApproximateQuantilesCombineFn} that's like
     * this one except that it uses the specified {@code maxNumElements}
     * value.  Does not modify this combiner.
     *
     * <p> See {@link #create(int, Comparator, long, double)} for more
     * information about the meaning of {@code maxNumElements}.
     */
    public ApproximateQuantilesCombineFn<T, C> withMaxInputSize(
        long maxNumElements) {
      return create(numQuantiles, compareFn, maxNumElements, maxNumElements);
    }

    /**
     * Creates an approximate quantiles combiner with the given
     * {@code compareFn} and desired number of quantiles.  A total of
     * {@code numQuantiles} elements will appear in the output list,
     * including the minimum and maximum.
     *
     * <p> The {@code Comparator} must be {@code Serializable}.
     *
     * <p> The default error bound is {@code epsilon} which is holds as long
     * as the number of elements is less than {@code maxNumElements}.
     * Specifically, if one considers the input as a sorted list x_1, ..., x_N,
     * then the distance between the each exact quantile x_c and its
     * approximation x_c' is bounded by {@code |c - c'| < epsilon * N}.
     * Note that these errors are worst-case scenarios; in practice the accuracy
     * tends to be much better.
     */
    public static <T, C extends Comparator<T> & Serializable>
    ApproximateQuantilesCombineFn<T, C> create(
        int numQuantiles,
        C compareFn,
        long maxNumElements,
        double epsilon) {
      // Compute optimal b and k.
      int b = 2;
      while ((b - 2) * (1 << (b - 2)) < epsilon * maxNumElements) {
        b++;
      }
      b--;
      int k = Math.max(2, (int) Math.ceil(maxNumElements / (1 << (b - 1))));
      return new ApproximateQuantilesCombineFn<>(
          numQuantiles, compareFn, k, b, epsilon, maxNumElements);
    }

    private ApproximateQuantilesCombineFn(int numQuantiles,
                                          C compareFn,
                                          int bufferSize,
                                          int numBuffers,
                                          double epsilon,
                                          long maxNumElements) {
      Preconditions.checkArgument(numQuantiles >= 2);
      Preconditions.checkArgument(bufferSize >= 2);
      Preconditions.checkArgument(numBuffers >= 2);
      Preconditions.checkArgument(compareFn instanceof Serializable);
      this.numQuantiles = numQuantiles;
      this.compareFn = compareFn;
      this.bufferSize = bufferSize;
      this.numBuffers = numBuffers;
      this.epsilon = epsilon;
      this.maxNumElements = maxNumElements;
    }

    @Override
    public QuantileState createAccumulator() {
      return new QuantileState();
    }

    @Override
    public Coder<QuantileState> getAccumulatorCoder(
        CoderRegistry registry, Coder<T> elementCoder) {
      return new QuantileStateCoder(elementCoder);
    }

    /**
     * Compact summarization of a collection on which quantiles can be
     * estimated.
     */
    class QuantileState
        implements AccumulatingCombineFn.Accumulator
        <T, ApproximateQuantilesCombineFn<T, C>.QuantileState, List<T>> {

      private T min;
      private T max;

      /**
       * The set of buffers, ordered by level from smallest to largest.
       */
      private PriorityQueue<QuantileBuffer> buffers =
          new PriorityQueue<>(numBuffers + 1);

      /**
       * The algorithm requires that the manipulated buffers always be filled
       * to capacity to perform the collapse operation.  This operation can
       * be extended to buffers of varying sizes by introducing the notion of
       * fractional weights, but it's easier to simply combine the remainders
       * from all shards into new, full buffers and then take them into account
       * when computing the final output.
       */
      private List<T> unbufferedElements = Lists.newArrayList();

      public QuantileState() { }

      public QuantileState(T elem) {
        min = elem;
        max = elem;
        unbufferedElements.add(elem);
      }

      public QuantileState(T min, T max, Collection<T> unbufferedElements,
                           Collection<QuantileBuffer> buffers) {
        this.min = min;
        this.max = max;
        this.unbufferedElements.addAll(unbufferedElements);
        this.buffers.addAll(buffers);
      }

      /**
       * Add a new element to the collection being summarized by this state.
       */
      @Override
      public void addInput(T elem) {
        if (isEmpty()) {
          min = max = elem;
        } else if (compareFn.compare(elem, min) < 0) {
          min = elem;
        } else if (compareFn.compare(elem, max) > 0) {
          max = elem;
        }
        addUnbuffered(elem);
      }

      /**
       * Add a new buffer to the unbuffered list, creating a new buffer and
       * collapsing if needed.
       */
      private void addUnbuffered(T elem) {
        unbufferedElements.add(elem);
        if (unbufferedElements.size() == bufferSize) {
          Collections.sort(unbufferedElements, compareFn);
          buffers.add(new QuantileBuffer(unbufferedElements));
          unbufferedElements = Lists.newArrayListWithCapacity(bufferSize);
          collapseIfNeeded();
        }
      }

      /**
       * Updates this as if adding all elements seen by other.
       */
      @Override
      public void mergeAccumulator(QuantileState other) {
        if (other.isEmpty()) {
          return;
        }
        if (min == null || compareFn.compare(other.min, min) < 0) {
          min = other.min;
        }
        if (max == null || compareFn.compare(other.max, max) > 0) {
          max = other.max;
        }
        for (T elem : other.unbufferedElements) {
          addUnbuffered(elem);
        }
        buffers.addAll(other.buffers);
        collapseIfNeeded();
      }

      public boolean isEmpty() {
        return unbufferedElements.size() == 0 && buffers.size() == 0;
      }

      private void collapseIfNeeded() {
        while (buffers.size() > numBuffers) {
          List<QuantileBuffer> toCollapse = Lists.newArrayList();
          toCollapse.add(buffers.poll());
          toCollapse.add(buffers.poll());
          int minLevel = toCollapse.get(1).level;
          while (!buffers.isEmpty() && buffers.peek().level == minLevel) {
            toCollapse.add(buffers.poll());
          }
          buffers.add(collapse(toCollapse));
        }
      }

      private QuantileBuffer collapse(Iterable<QuantileBuffer> buffers) {
        int newLevel = 0;
        long newWeight = 0;
        for (QuantileBuffer buffer : buffers) {
          // As presented in the paper, there should always be at least two
          // buffers of the same (minimal) level to collapse, but it is possible
          // to violate this condition when combining buffers from independently
          // computed shards.  If they differ we take the max.
          newLevel = Math.max(newLevel, buffer.level + 1);
          newWeight += buffer.weight;
        }
        List<T> newElements =
            interpolate(buffers, bufferSize, newWeight, offset(newWeight));
        return new QuantileBuffer(newLevel, newWeight, newElements);
      }

      /**
       * Outputs numQuantiles elements consisting of the minimum, maximum, and
       * numQuantiles - 2 evenly spaced intermediate elements.
       *
       * Returns the empty list if no elements have been added.
       */
      @Override
      public List<T> extractOutput() {
        if (isEmpty()) {
          return Lists.newArrayList();
        }
        long totalCount = unbufferedElements.size();
        for (QuantileBuffer buffer : buffers) {
          totalCount += bufferSize * buffer.weight;
        }
        List<QuantileBuffer> all = Lists.newArrayList(buffers);
        if (!unbufferedElements.isEmpty()) {
          Collections.sort(unbufferedElements, compareFn);
          all.add(new QuantileBuffer(unbufferedElements));
        }
        double step = 1.0 * totalCount / (numQuantiles - 1);
        double offset = (1.0 * totalCount - 1) / (numQuantiles - 1);
        List<T> quantiles = interpolate(all, numQuantiles - 2, step, offset);
        quantiles.add(0, min);
        quantiles.add(max);
        return quantiles;
      }
    }

    /**
     * A single buffer in the sense of the referenced algorithm.
     */
    private class QuantileBuffer implements Comparable<QuantileBuffer> {
      private int level;
      private long weight;
      private List<T> elements;

      public QuantileBuffer(List<T> elements) {
        this(0, 1, elements);
      }

      public QuantileBuffer(int level, long weight, List<T> elements) {
        this.level = level;
        this.weight = weight;
        this.elements = elements;
      }

      @Override
      public int compareTo(QuantileBuffer other) {
        return this.level - other.level;
      }

      @Override
      public String toString() {
        return "QuantileBuffer["
            + "level=" + level
            + ", weight="
            + weight + ", elements=" + elements + "]";
      }

      public Iterator<WeightedElement<T>> weightedIterator() {
        return new UnmodifiableIterator<WeightedElement<T>>() {
          Iterator<T> iter = elements.iterator();
          @Override public boolean hasNext() { return iter.hasNext(); }
          @Override public WeightedElement<T> next() {
            return WeightedElement.of(weight, iter.next());
          }
        };
      }
    }

    /**
     * Coder for QuantileState.
     */
    private class QuantileStateCoder extends CustomCoder<QuantileState> {

      private final Coder<T> elementCoder;
      private final Coder<List<T>> elementListCoder;

      public QuantileStateCoder(Coder<T> elementCoder) {
        this.elementCoder = elementCoder;
        this.elementListCoder = ListCoder.of(elementCoder);
      }

      @Override
      public void encode(
          QuantileState state, OutputStream outStream, Coder.Context context)
          throws CoderException, IOException {
        Coder.Context nestedContext = context.nested();
        elementCoder.encode(state.min, outStream, nestedContext);
        elementCoder.encode(state.max, outStream, nestedContext);
        elementListCoder.encode(
            state.unbufferedElements, outStream, nestedContext);
        BigEndianIntegerCoder.of().encode(
            state.buffers.size(), outStream, nestedContext);
        for (QuantileBuffer buffer : state.buffers) {
          encodeBuffer(buffer, outStream, nestedContext);
        }
      }

      @Override
      public QuantileState decode(InputStream inStream, Coder.Context context)
          throws CoderException, IOException {
        Coder.Context nestedContext = context.nested();
        T min = elementCoder.decode(inStream, nestedContext);
        T max = elementCoder.decode(inStream, nestedContext);
        List<T> unbufferedElements =
            elementListCoder.decode(inStream, nestedContext);
        int numBuffers =
            BigEndianIntegerCoder.of().decode(inStream, nestedContext);
        List<QuantileBuffer> buffers = new ArrayList<>(numBuffers);
        for (int i = 0; i < numBuffers; i++) {
          buffers.add(decodeBuffer(inStream, nestedContext));
        }
        return new QuantileState(min, max, unbufferedElements, buffers);
      }

      private void encodeBuffer(
          QuantileBuffer buffer, OutputStream outStream, Coder.Context context)
          throws CoderException, IOException {
        DataOutputStream outData = new DataOutputStream(outStream);
        outData.writeInt(buffer.level);
        outData.writeLong(buffer.weight);
        elementListCoder.encode(buffer.elements, outStream, context);
      }

      private QuantileBuffer decodeBuffer(
          InputStream inStream, Coder.Context context)
          throws IOException, CoderException {
        DataInputStream inData = new DataInputStream(inStream);
        return new QuantileBuffer(
            inData.readInt(),
            inData.readLong(),
            elementListCoder.decode(inStream, context));
      }

      /**
       * Notifies ElementByteSizeObserver about the byte size of the
       * encoded value using this coder.
       */
      @Override
      public void registerByteSizeObserver(
          QuantileState state,
          ElementByteSizeObserver observer,
          Coder.Context context)
          throws Exception {
        Coder.Context nestedContext = context.nested();
        elementCoder.registerByteSizeObserver(
            state.min, observer, nestedContext);
        elementCoder.registerByteSizeObserver(
            state.max, observer, nestedContext);
        elementListCoder.registerByteSizeObserver(
            state.unbufferedElements, observer, nestedContext);

        BigEndianIntegerCoder.of().registerByteSizeObserver(
            state.buffers.size(), observer, nestedContext);
        for (QuantileBuffer buffer : state.buffers) {
          observer.update(4L + 8);

          elementListCoder.registerByteSizeObserver(
              buffer.elements, observer, nestedContext);
        }
      }

      @Override
      public boolean isDeterministic() {
        return elementListCoder.isDeterministic();
      }
    }

    /**
     * If the weight is even, we must round up our down.  Alternate between
     * these two options to avoid a bias.
     */
    private long offset(long newWeight) {
      if (newWeight % 2 == 1) {
        return (newWeight + 1) / 2;
      } else {
        offsetJitter = 2 - offsetJitter;
        return (newWeight + offsetJitter) / 2;
      }
    }

    /**
     * Emulates taking the ordered union of all elements in buffers, repeated
     * according to their weight, and picking out the (k * step + offset)-th
     * elements of this list for {@code 0 <= k < count}.
     */
    private List<T> interpolate(Iterable<QuantileBuffer> buffers,
                                int count, double step, double offset) {
      List<Iterator<WeightedElement<T>>> iterators = Lists.newArrayList();
      for (QuantileBuffer buffer : buffers) {
        iterators.add(buffer.weightedIterator());
      }
      // Each of the buffers is already sorted by element.
      Iterator<WeightedElement<T>> sorted = Iterators.mergeSorted(
          iterators,
          new Comparator<WeightedElement<T>>() {
            @Override
            public int compare(WeightedElement<T> a, WeightedElement<T> b) {
              return compareFn.compare(a.value, b.value);
            }
          });

      List<T> newElements = Lists.newArrayListWithCapacity(count);
      WeightedElement<T> weightedElement = sorted.next();
      double current = weightedElement.weight;
      for (int j = 0; j < count; j++) {
        double target = j * step + offset;
        while (current <= target && sorted.hasNext()) {
          weightedElement = sorted.next();
          current += weightedElement.weight;
        }
        newElements.add(weightedElement.value);
      }
      return newElements;
    }

    /** An element and its weight. */
    private static class WeightedElement<T> {
      public long weight;
      public T value;
      private WeightedElement(long weight, T value) {
        this.weight = weight;
        this.value = value;
      }
      public static <T> WeightedElement<T> of(long weight, T value) {
        return new WeightedElement<>(weight, value);
      }
    }
  }
}
