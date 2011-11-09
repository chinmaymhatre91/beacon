package net.beaconcontroller.core.internal;

public interface IOrderName<T> {
    /**
     * Returns the name for this object used in the ordering String
     * @param obj
     * @return
     */
    public String get(T obj);
}
