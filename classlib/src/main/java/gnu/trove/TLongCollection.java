package gnu.trove;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.procedure.TLongProcedure;

public interface TLongCollection {
    long serialVersionUID = 0L;
    long getNoEntryValue();
    int size();
    boolean isEmpty();
    boolean contains(long val);
    TLongIterator iterator();
    long[] toArray();
    long[] toArray(long[] dest);
    boolean add(long val);
    boolean remove(long val);
    boolean containsAll(java.util.Collection<?> c);
    boolean containsAll(TLongCollection c);
    boolean containsAll(long[] a);
    boolean addAll(java.util.Collection<? extends java.lang.Long> c);
    boolean addAll(TLongCollection c);
    boolean addAll(long[] a);
    boolean retainAll(java.util.Collection<?> c);
    boolean retainAll(TLongCollection c);
    boolean retainAll(long[] a);
    boolean removeAll(java.util.Collection<?> c);
    boolean removeAll(TLongCollection c);
    boolean removeAll(long[] a);
    void clear();
    boolean forEach(TLongProcedure procedure);
    boolean equals(Object o);
    int hashCode();
}
