package de.rwth_aachen.vizzey;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

//Databuffer class
//Each databuffer can be identified by a name (mapped in phyphoxExperiment class)
//The actual databuffer is implemented as a BlockingQueue, but we keep track of a maximum size ourselves.

interface BufferNotification {
    void notifyUpdate(boolean clear, boolean reset); //Notify that a buffer has changed. Also notify if the buffer has been cleared (for example during
}

public class DataBuffer implements Serializable {
    public String name; //The key name
    private BlockingQueue<Double> buffer; //The actual buffer (will be initialized as ArrayBlockingQueue which is Serializable)
    public int size; //The target size
    public double value; //The last added value for easy access and graceful returning NaN for empty buffers
    public boolean isStatic = false; //If set to static, this buffer should only be filled once and cannot be cleared thereafter
    public Double [] init = new Double[0];
    public boolean staticAndSet = false;

    //Analysis modules and graphs can register to receive notifications if a buffer changes.
    public Set<BufferNotification> updateListeners = new HashSet<>();

    //This is not logically connected to the data Buffer itself, but it is more efficient to calculate only changes to the buffer, where we can keep track of those changes
    transient private FloatBufferRepresentation floatCopy = null; //If a float copy has been requested, we keep it around as it will probably be requested again...
    transient private FloatBufferRepresentation floatCopyBarValue = null; //If a float copy for bar charts has been requested, we keep it around as it will probably be requested again...
    transient private FloatBufferRepresentation floatCopyBarAxis = null; //If a float copy for bar charts has been requested, we keep it around as it will probably be requested again...
    transient private List<ExperimentTimeReferenceSet> experimentTimeReferenceSets = null;
    transient public final Object experimentTimeReferenceSetsLock = new Object();
    ExperimentTimeReference experimentTimeReference;
    boolean linearTime = false;
    private double lineWidth = 1.0; //Used to calculate the right geometry of bar charts

    private int floatCopyCapacity = 0;
    private int floatCopyBarValueCapacity = 0;
    private int floatCopyBarAxisCapacity = 0;

    private double min = Double.NaN;
    private double max = Double.NaN;

    //Contructor. Set key name and target size.
    protected DataBuffer(String name, int size, ExperimentTimeReference experimentTimeReference) {
        this.experimentTimeReference = experimentTimeReference;
        this.size = size;
        this.name = name;
        if (size > 0)
            this.buffer = new ArrayBlockingQueue<>(size);
        else
            this.buffer = new LinkedBlockingQueue<>();
        this.value = Double.NaN;
    }

    //Analysis and view modules can register to learn about updates
    public void register(BufferNotification listener) {
        updateListeners.add(listener);
    }

    public void unregister(BufferNotification listener) {
        updateListeners.remove(listener);
    }

    public void notifyListeners(boolean clear, boolean reset) {
        for (BufferNotification listener : updateListeners) {
            listener.notifyUpdate(clear, reset);
        }
    }

    private void putBarAxisValue(FloatBuffer buffer, double last, double value, int offset) {
        if (Double.isNaN(last) || Double.isNaN(value)) {
            buffer.put(offset, Float.NaN);
            buffer.put(offset + 1, Float.NaN);
            buffer.put(offset + 2, Float.NaN);
            buffer.put(offset + 3, Float.NaN);
            buffer.put(offset + 4, Float.NaN);
            buffer.put(offset + 5, Float.NaN);
        } else {
            float d = (float)((value-last)*(1.-lineWidth)/2.);
            float vOff = (float)value - d;
            float lOff = (float)last + d;
            buffer.put(offset, lOff);
            buffer.put(offset + 1, vOff);
            buffer.put(offset + 2, lOff);
            buffer.put(offset + 3, vOff);
            buffer.put(offset + 4, lOff);
            buffer.put(offset + 5, vOff);
        }
    }

    private void putBarValueValue(FloatBuffer buffer, double last, int offset) {
        if (Double.isNaN(last)) {
            buffer.put(offset, Float.NaN);
            buffer.put(offset + 1, Float.NaN);
            buffer.put(offset + 2, Float.NaN);
            buffer.put(offset + 3, Float.NaN);
            buffer.put(offset + 4, Float.NaN);
            buffer.put(offset + 5, Float.NaN);
        } else {
            buffer.put(offset, 0.f);
            buffer.put(offset + 1, 0.f);
            buffer.put(offset + 2, (float) last);
            buffer.put(offset + 3, (float) last);
            buffer.put(offset + 4, (float) last);
            buffer.put(offset + 5, 0.f);
        }
    }

    //Append a value to the buffer.
    private void append(double value, boolean notify) {
        if (staticAndSet)
            return;
        double last = this.value;
        this.value = value; //Update last value
        if (this.size > 0 && buffer.size()+1 > this.size) { //If the buffer becomes larger than the target size, remove the first item (queue!)
            buffer.poll();
            min = Double.NaN;
            max = Double.NaN;
            if (floatCopy != null) {
                synchronized (floatCopy.lock) {
                    floatCopy.offset++;
                    floatCopy.size--;
                }
            }
            if (floatCopyBarAxis != null) {
                synchronized (floatCopyBarAxis.lock) {
                    floatCopyBarAxis.offset+=6;
                    floatCopyBarAxis.size-=6;
                }
            }
            if (floatCopyBarValue != null) {
                synchronized (floatCopyBarValue.lock) {
                    floatCopyBarValue.offset+=6;
                    floatCopyBarValue.size-=6;
                }
            }
            if (experimentTimeReferenceSets != null) {
                synchronized (experimentTimeReferenceSetsLock) {
                    for (int i = experimentTimeReferenceSets.size()-1; i >= 0; i--) {
                        experimentTimeReferenceSets.get(i).index--;
                    }
                    if (experimentTimeReferenceSets.get(0).index < 0) {
                        experimentTimeReferenceSets.get(0).index = 0;
                        experimentTimeReferenceSets.get(0).count--;
                    }
                    if (experimentTimeReferenceSets.get(0).count <= 0) {
                        experimentTimeReferenceSets.remove(0);
                    }
                }
            }

        }
        buffer.add(value);
        if (!Double.isNaN(min) && !Double.isInfinite(min))
            min = Math.min(min, value);
        if (!Double.isNaN(max) && !Double.isInfinite(max))
            max = Math.max(max, value);

        if (floatCopy != null) {
            synchronized (floatCopy.lock) {
                if (floatCopyCapacity < floatCopy.offset + floatCopy.size + 1) {
                    if (floatCopyCapacity < buffer.size() * 2)
                        floatCopyCapacity *= 2;
                    FloatBuffer newData = ByteBuffer.allocateDirect(floatCopyCapacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    floatCopy.data.position(floatCopy.offset);
                    newData.put(floatCopy.data);
                    floatCopy.data = newData;
                    floatCopy.offset = 0;
                }
                floatCopy.data.put(floatCopy.offset + floatCopy.size, (float) value);
                floatCopy.size++;
            }
        }

        if (floatCopyBarValue != null) {
            synchronized (floatCopyBarValue.lock) {
                if (floatCopyBarValueCapacity < floatCopyBarValue.offset + floatCopyBarValue.size + 6) {
                    if (floatCopyBarValueCapacity < buffer.size() * 2 * 6)
                        floatCopyBarValueCapacity *= 2;
                    FloatBuffer newData = ByteBuffer.allocateDirect(floatCopyBarValueCapacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    floatCopyBarValue.data.position(floatCopyBarValue.offset);
                    newData.put(floatCopyBarValue.data);
                    floatCopyBarValue.data = newData;
                    floatCopyBarValue.offset = 0;
                }

                putBarValueValue(floatCopyBarValue.data, last, floatCopyBarValue.offset + floatCopyBarValue.size);
                floatCopyBarValue.size += 6;
            }
        }
        if (floatCopyBarAxis != null) {
            synchronized (floatCopyBarAxis.lock) {
                if (floatCopyBarAxisCapacity < floatCopyBarAxis.offset + floatCopyBarAxis.size + 6) {
                    if (floatCopyBarAxisCapacity < buffer.size() * 2 * 6)
                        floatCopyBarAxisCapacity *= 2;
                    FloatBuffer newData = ByteBuffer.allocateDirect(floatCopyBarAxisCapacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    floatCopyBarAxis.data.position(floatCopyBarAxis.offset);
                    newData.put(floatCopyBarAxis.data);
                    floatCopyBarAxis.data = newData;
                    floatCopyBarAxis.offset = 0;
                }

                putBarAxisValue(floatCopyBarAxis.data, last, value, floatCopyBarAxis.offset + floatCopyBarAxis.size);
                floatCopyBarAxis.size += 6;
            }
        }
        if (experimentTimeReferenceSets != null) {
            synchronized (experimentTimeReferenceSetsLock) {
                int referenceIndex = linearTime ? experimentTimeReference.getReferenceIndexFromLinearTime(value) : experimentTimeReference.getReferenceIndexFromExperimentTime(value);
                if (experimentTimeReferenceSets.size() > 0) {
                    ExperimentTimeReferenceSet lastSet = experimentTimeReferenceSets.get(experimentTimeReferenceSets.size()-1);
                    if (lastSet.referenceIndex == referenceIndex) {
                        lastSet.count++;
                    } else {
                        experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastSet.index + lastSet.count, 1, experimentTimeReference.getExperimentTimeReferenceByIndex(referenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(referenceIndex), referenceIndex, experimentTimeReference.getPausedByIndex(referenceIndex)));
                    }
                } else {
                    experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(0, 1, experimentTimeReference.getExperimentTimeReferenceByIndex(referenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(referenceIndex), referenceIndex, experimentTimeReference.getPausedByIndex(referenceIndex)));
                }
            }
        }

        if (notify) {
            notifyListeners(false, false);
        }
    }

    public void append(double value) {
        append(value, true);
    }

    //Get the number of elements actually filled into the buffer
    public int getFilledSize() {
        return buffer.size();
    }

    //Append a double-array with [count] entries.
    public void append(Double value[], Integer count, boolean notify) {
        for (int i = 0; i < count; i++)
            append(value[i], false);
        if (notify)
            notifyListeners(false, false);
    }

    public void append(Double value[], Integer count) {
        append(value, count, true);
    }

    //Append a short-array with [count] entries. This will be scaled to [-1:+1] and is used for audio data
    public void append(short value[], int count) {
        for (int i = 0; i < count; i++)
            append((double)value[i]/(double)Short.MAX_VALUE, true); //Normalize to [-1:+1] and append
        notifyListeners(false, false);
    }

    //Wrapper function to set this buffer's static-state
    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    //Wrapper function to set this buffer's static-state
    public void setInit(Double[] init) {
        this.init = init;
        this.append(init, init.length, false);
        if (init.length > 0)
            markSet();
    }

    //Delete all data and set last item to NaN (if not static)
    public void clear(boolean reset, boolean notify) {
        if (isStatic) {
            if (reset)
                staticAndSet = false;
            else
                return;
        }
        buffer.clear();
        value = Double.NaN;
        if (floatCopy != null) {
            synchronized (floatCopy.lock) {
                //Instead of just resetting the offset and length to zero, we abandon the buffer, so
                //a new one will be created. The reason to do this is that the copy of the old
                //buffer remains in tact if a graph has just received it as a data source. So the
                //data is available until the graph gets a new reference and it is removed by the
                //garbage collector. Otherwise the reference might be reset before the graph is
                //redrawn which results in flickering.
                floatCopy = null;
            }
        }
        if (floatCopyBarValue != null) {
            //see above
            synchronized (floatCopyBarValue.lock) {
                floatCopyBarValue = null;
            }
        }
        if (floatCopyBarAxis != null) {
            //see above
            synchronized (floatCopyBarAxis.lock) {
                floatCopyBarAxis = null;
            }
        }
        if (experimentTimeReferenceSets != null) {
            synchronized (experimentTimeReferenceSetsLock) {
                experimentTimeReferenceSets = null;
            }
        }
        min = Double.NaN;
        max = Double.NaN;

        if (reset)
            this.append(init, init.length);

        if (notify)
            notifyListeners(true, reset);
    }

    public void clear(boolean reset) {
        clear(reset, true);
    }

    public void markSet() {
        if (isStatic)
            staticAndSet = true;
    }

    //Retrieve the iterator of the BlockingQueue
    public Iterator<Double> getIterator() {
        return buffer.iterator();
    }

    //Get all values as a double array
    public Double[] getArray() {
        Double ret[] = new Double[buffer.size()];
        return buffer.toArray(ret);
    }

    public FloatBufferRepresentation getFloatBuffer() {
        int n = buffer.size();
        if (n == 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopy == null) {
            FloatBuffer data = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            floatCopyCapacity = n;
            Iterator it = getIterator();
            int i = 0;
            while (it.hasNext() && i < n) {
                double x = (double) it.next();
                if ((Double.isNaN(x) || Double.isInfinite(x)))
                    data.put(-3.4e38f);
                else
                    data.put((float) x);
                //This is a bit hacky, but should work in any reasonable situation. Some OpenGL ES
                // implementations (HTC One X, some Samsungs, ...) seem to not properly handle NaN,
                // which makes it impossible to detect invalid data points in the vertex and/or
                // fragment shader. The behavior seems to be unspecified with some devices
                // interpreting NaN as zero (Samsung?) and some devices failing all subsequent
                // calculations ans eventually interpreting the resulting NaN as zero (HTC One X
                // draws a line to the canvas zero coordinate.
                // The value 3.4e38f is close to the smallest possible number represented by a
                // float32 (I do not dare to use the exact minimum as it might be altered by
                // rounding or shader optimization), so it should not occur by accident in any
                // reasonable use case and we use it to tag invalid values. The vertex shader will
                // simply check for values below -3.3e38f and mark them for the fragment shader to
                // be discarded.
                i++;
            }
            floatCopy = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopy;
    }

    public FloatBufferRepresentation getFloatBufferBarAxis(double lineWidth) {
        this.lineWidth = lineWidth;
        int n = buffer.size()*6;
        if (n <= 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopyBarAxis == null) {
            FloatBuffer data = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            floatCopyBarAxisCapacity = n;
            Iterator it = getIterator();
            int i = 0;
            double last = Double.NaN;
            while (it.hasNext() && i < n) {
                double value = (double)it.next();
                putBarAxisValue(data, last, value, i);
                last = value;
                i+=6;
            }
            floatCopyBarAxis = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopyBarAxis;
    }

    public FloatBufferRepresentation getFloatBufferBarValue() {
        int n = buffer.size()*6;
        if (n <= 0)
            return new FloatBufferRepresentation(null, 0, 0);

        if (floatCopyBarValue == null) {
            FloatBuffer data = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            floatCopyBarValueCapacity = n;
            Iterator it = getIterator();
            int i = 0;
            double last = Double.NaN;
            while (it.hasNext() && i < n) {
                double value = (double)it.next();
                putBarValueValue(data, last, i);
                last = value;
                i+=6;
            }
            floatCopyBarValue = new FloatBufferRepresentation(data, 0, n);
        }
        return floatCopyBarValue;
    }

    public List<ExperimentTimeReferenceSet> getExperimentTimeReferenceSets(boolean isLinearTime) {
        if (buffer.size() <= 0)
            return new ArrayList<>();

        if (isLinearTime != linearTime) {
            linearTime = isLinearTime;
            synchronized (experimentTimeReferenceSetsLock) {
                experimentTimeReferenceSets = null;
            }
        }

        if (experimentTimeReferenceSets == null) {
            experimentTimeReferenceSets = new ArrayList<>();
            Iterator it = getIterator();
            int lastReferenceIndex = -1;
            int lastchange = 0;
            int i = 0;
            while (it.hasNext()) {
                double value = (double)it.next();
                int referenceIndex = isLinearTime ? experimentTimeReference.getReferenceIndexFromLinearTime(value) : experimentTimeReference.getReferenceIndexFromExperimentTime(value);
                if (lastReferenceIndex < 0)
                    lastReferenceIndex = referenceIndex;
                else if (lastReferenceIndex != referenceIndex) {
                    experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastchange, i-lastchange, experimentTimeReference.getExperimentTimeReferenceByIndex(lastReferenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(lastReferenceIndex), lastReferenceIndex, experimentTimeReference.getPausedByIndex(lastReferenceIndex)));
                    lastchange = i;
                    lastReferenceIndex = referenceIndex;
                }
                i++;
            }
            experimentTimeReferenceSets.add(new ExperimentTimeReferenceSet(lastchange, i-lastchange, experimentTimeReference.getExperimentTimeReferenceByIndex(lastReferenceIndex), experimentTimeReference.getSystemTimeReferenceByIndex(lastReferenceIndex), lastReferenceIndex, experimentTimeReference.getPausedByIndex(lastReferenceIndex)));
        }
        return experimentTimeReferenceSets;
    }

    //Get all values as a short array. The data will be scaled so that (-/+)1 matches (-/+)Short.MAX_VALUE, used for audio data
    public short[] getShortArray() {
        short[] ret = new short[buffer.size()];
        Iterator it = getIterator();
        int i = 0;
        while (it.hasNext()) {
            ret[i] = (short)(((double)it.next())*(Short.MAX_VALUE)); //Rescale data to short range
            i++;
        }
        return ret;
    }

    public DataBuffer copy() {
        DataBuffer db = new DataBuffer(this.name, this.size, this.experimentTimeReference);
        db.append(this.getArray(), this.getFilledSize());
        db.isStatic = this.isStatic;
        return db;
    }

    public double getMin() {
        if (!Double.isNaN(min)) {
            return min;
        }

        if (buffer.size() == 0)
            return Double.NaN;

        min = Collections.min(buffer, new minComparator());

        if (Double.isInfinite(min))
            min = Double.NaN;

        return min;
    }

    public double getMax() {
        if (!Double.isNaN(max)) {
            return max;
        }

        if (buffer.size() == 0)
            return Double.NaN;

        max = Collections.max(buffer, new maxComparator());

        if (Double.isInfinite(max))
            max = Double.NaN;

        return max;
    }

    class minComparator implements Comparator<Double> {
        public int compare(Double a, Double b) {
            if (Double.isNaN(a) || Double.isInfinite(a))
                return 1;
            if (Double.isNaN(b) || Double.isInfinite(b))
                return -1;
            return Double.compare(a, b);
        }
    }

    class maxComparator implements Comparator<Double> {
        public int compare(Double a, Double b) {
            if (Double.isNaN(a) || Double.isInfinite(a))
                return -1;
            if (Double.isNaN(b) || Double.isInfinite(b))
                return 1;
            return Double.compare(a, b);
        }
    }
}

class FloatBufferRepresentation {
    FloatBuffer data;
    int size;
    int offset;
    transient public final Object lock = new Object();

    FloatBufferRepresentation(FloatBuffer data, int offset, int size) {
        this.data = data;
        this.size = size;
        this.offset = offset;
    }
}

class ExperimentTimeReferenceSet {
    int index;
    int count;
    int referenceIndex;
    double experimentTime;
    long systemTime;
    boolean isPaused;

    ExperimentTimeReferenceSet(int index, int count, double experimentTime, long systemTime, int referenceIndex, boolean isPaused) {
        this.index = index;
        this.count = count;
        this.experimentTime = experimentTime;
        this.systemTime = systemTime;
        this.referenceIndex = referenceIndex;
        this.isPaused = isPaused;
    }
}
