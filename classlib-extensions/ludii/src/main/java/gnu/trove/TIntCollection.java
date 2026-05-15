package gnu.trove;

import gnu.trove.iterator.TIntIterator;
import gnu.trove.procedure.TIntProcedure;

public interface TIntCollection {
    long serialVersionUID = 0L;
    int getNoEntryValue();
    int size();
    boolean isEmpty();
    boolean contains(int val);
    TIntIterator iterator();
    int[] toArray();
    int[] toArray(int[] dest);
    boolean add(int val);
    boolean remove(int val);
    boolean containsAll(java.util.Collection<?> c);
    boolean containsAll(TIntCollection c);
    boolean containsAll(int[] a);
    boolean addAll(java.util.Collection<? extends java.lang.Integer> c);
    boolean addAll(TIntCollection c);
    boolean addAll(int[] a);
    boolean retainAll(java.util.Collection<?> c);
    boolean retainAll(TIntCollection c);
    boolean retainAll(int[] a);
    boolean removeAll(java.util.Collection<?> c);
    boolean removeAll(TIntCollection c);
    boolean removeAll(int[] a);
    void clear();
    boolean forEach(TIntProcedure procedure);
    boolean equals(Object o);
    int hashCode();
}
