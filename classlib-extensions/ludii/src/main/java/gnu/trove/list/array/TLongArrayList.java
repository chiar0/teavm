package gnu.trove.list.array;

import java.util.Random;

import gnu.trove.TLongCollection;
import gnu.trove.iterator.TLongIterator;

/**
 * TeaVM-compatible replacement for Trove's TLongArrayList.
 * Uses internal long[] storage instead of sun.misc.Unsafe.
 */
public class TLongArrayList implements java.io.Externalizable {

    protected long no_entry_value;
    private long[] data;
    private int size;

    private static final int DEFAULT_CAPACITY = 10;

    public TLongArrayList() { this(DEFAULT_CAPACITY); }
    public TLongArrayList(int capacity) { data = new long[Math.max(1, capacity)]; size = 0; }
    public TLongArrayList(long[] values) { data = values.clone(); size = values.length; }
    public TLongArrayList(TLongArrayList other) {
        data = new long[other.size];
        System.arraycopy(other.data, 0, data, 0, other.size);
        size = other.size;
        no_entry_value = other.no_entry_value;
    }
    public TLongArrayList(TLongCollection c) {
        this(c.size());
        if (c instanceof TLongArrayList) {
            TLongArrayList other = (TLongArrayList) c;
            System.arraycopy(other.data, 0, data, 0, other.size);
            size = other.size;
        } else {
            TLongIterator it = c.iterator();
            while (it.hasNext()) {
                add(it.next());
            }
        }
    }

    public long getNoEntryValue() { return no_entry_value; }
    public void setNoEntryValue(long v) { no_entry_value = v; }

    private void grow(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = Math.max(data.length + (data.length >> 1), minCapacity);
        long[] nd = new long[newCap];
        System.arraycopy(data, 0, nd, 0, size);
        data = nd;
    }

    // ---- Primitive API ----

    public boolean add(long val) {
        grow(size + 1);
        data[size++] = val;
        return true;
    }

    public long get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return data[index];
    }

    public long set(int index, long val) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        long old = data[index];
        data[index] = val;
        return old;
    }

    public boolean contains(long val) {
        return indexOf(val) >= 0;
    }

    public int indexOf(long val) {
        for (int i = 0; i < size; i++) if (data[i] == val) return i;
        return -1;
    }

    public boolean remove(long val) {
        int idx = indexOf(val);
        if (idx < 0) return false;
        removeAt(idx);
        return true;
    }

    public long removeAt(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        long old = data[index];
        int moved = size - index - 1;
        if (moved > 0) System.arraycopy(data, index + 1, data, index, moved);
        size--;
        return old;
    }

    public void insert(int index, long value) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException();
        grow(size + 1);
        System.arraycopy(data, index, data, index + 1, size - index);
        data[index] = value;
        size++;
    }

    public void reset() { clear(); }

    public long[] toArray() {
        long[] result = new long[size];
        System.arraycopy(data, 0, result, 0, size);
        return result;
    }

    public long[] toArray(long[] dest) {
        if (dest.length < size) dest = new long[size];
        System.arraycopy(data, 0, dest, 0, size);
        return dest;
    }

    public static TLongArrayList wrap(long[] a) {
        TLongArrayList list = new TLongArrayList(0);
        list.data = a;
        list.size = a.length;
        return list;
    }

    public boolean addAll(TLongCollection c) {
        if (c instanceof TLongArrayList) {
            TLongArrayList other = (TLongArrayList) c;
            grow(size + other.size);
            System.arraycopy(other.data, 0, data, size, other.size);
            size += other.size;
            return other.size > 0;
        }
        boolean modified = false;
        for (gnu.trove.iterator.TLongIterator it = c.iterator(); it.hasNext(); ) {
            add(it.next());
            modified = true;
        }
        return modified;
    }

    // ---- Boxed overloads for generic interop ----

    public int size() { return size; }
    public Long get(Integer index) { return get(index.intValue()); }
    public boolean add(Long val) { return add(val.longValue()); }
    public Long set(Integer index, Long val) { return set(index.intValue(), val.longValue()); }
    public Long remove(Integer index) { return removeAt(index.intValue()); }
    public void clear() { size = 0; }
    public boolean isEmpty() { return size == 0; }

    // ---- Additional API ----

    public long getQuick(int index) {
        if (index < 0 || index >= size) return no_entry_value;
        return data[index];
    }

    public void setQuick(int index, long value) {
        if (index < 0) return;
        grow(index + 1);
        data[index] = value;
        if (index >= size) size = index + 1;
    }

    public void ensureCapacity(int capacity) { grow(capacity); }

    public void fill(long value) {
        for (int i = 0; i < size; i++) data[i] = value;
    }

    public void reverse() {
        for (int i = 0, j = size - 1; i < j; i++, j--) {
            long tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public void sort() { java.util.Arrays.sort(data, 0, size); }

    public void shuffle(Random random) {
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = data[i]; data[i] = data[j]; data[j] = tmp;
        }
    }

    public long max() {
        if (size == 0) throw new IllegalStateException();
        long m = data[0];
        for (int i = 1; i < size; i++) if (data[i] > m) m = data[i];
        return m;
    }

    public long min() {
        if (size == 0) throw new IllegalStateException();
        long m = data[0];
        for (int i = 1; i < size; i++) if (data[i] < m) m = data[i];
        return m;
    }

    public long sum() {
        long s = 0L;
        for (int i = 0; i < size; i++) s += data[i];
        return s;
    }

    public TLongIterator iterator() {
        return new TLongArrayIterator();
    }

    // ---- Externalizable (matches Ludii's real TLongArrayList format) ----

    @Override
    public void writeExternal(java.io.ObjectOutput out) throws java.io.IOException {
        out.writeByte(0); // version
        out.writeInt(size);
        out.writeLong(no_entry_value);
        out.writeInt(data.length);
        for (int i = 0; i < data.length; i++) out.writeLong(data[i]);
    }

    @Override
    public void readExternal(java.io.ObjectInput in) throws java.io.IOException, ClassNotFoundException {
        in.readByte(); // version
        size = in.readInt();
        no_entry_value = in.readLong();
        int capacity = in.readInt();
        data = new long[Math.max(1, capacity)];
        for (int i = 0; i < capacity; i++) data[i] = in.readLong();
    }

    class TLongArrayIterator implements TLongIterator {
        private int _index;
        private int _lastReturned = -1;

        public boolean hasNext() {
            return _index < size;
        }

        public long next() {
            if (!hasNext()) throw new IndexOutOfBoundsException();
            _lastReturned = _index;
            return data[_index++];
        }

        public void remove() {
            if (_lastReturned < 0) throw new IllegalStateException();
            int idx = _lastReturned;
            _index--;
            _lastReturned = -1;
            int moved = size - idx - 1;
            if (moved > 0) System.arraycopy(data, idx + 1, data, idx, moved);
            size--;
        }
    }
}
