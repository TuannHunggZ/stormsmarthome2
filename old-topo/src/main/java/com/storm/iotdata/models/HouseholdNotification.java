package com.storm.iotdata.models;

import com.google.gson.Gson;

/**
 * HouseholdNotification là cảnh báo bất thường ở cấp household cho một timeslice.
 */
public class HouseholdNotification extends Timeslice{
    // Loại cảnh báo theo ngưỡng min/avg/max.
    public Integer type;
    // House cha.
    public Integer houseId;
    // Household phát sinh cảnh báo.
    public Integer householdId;
    // Baseline min lịch sử.
    public Double min;
    // Baseline max lịch sử.
    public Double max;
    // Baseline avg lịch sử.
    public Double avg;
    // Giá trị thực tế tại timeslice.
    public Double value;
    // Thời điểm tạo notification.
    public Long timestamp;
    // Cờ persist.
    public Boolean saved=false;

    public HouseholdNotification(Integer type, HouseholdData data, HouseholdProp dataProp){
        super(data.year, data.month, data.day, data.sliceIndex, data.sliceGap);
        this.type=type;
        this.houseId=data.houseId;
        this.householdId=data.householdId;
        this.value=data.getAvg();
        this.min=dataProp.min;
        this.max=dataProp.max;
        this.avg=dataProp.avg;
        this.timestamp=System.currentTimeMillis();
    }

    public Integer getType() {
        return this.type;
    }

    public Integer getHouseId() {
        return this.houseId;
    }

    public Integer getHouseholdId() {
        return this.householdId;
    }

    public Double getMin() {
        return this.min;
    }

    public Double getMax() {
        return this.max;
    }

    public Double getAvg() {
        return this.avg;
    }

    public Double getValue() {
        return this.value;
    }

    public Long getTimestamp() {
        return this.timestamp;
    }

    public Boolean getSaved() {
        return this.saved;
    }

    public Boolean isSaved() {
        return this.saved;
    }

    public String toString() {
        Gson gson=new Gson();    
        return gson.toJson(this);
    }
}
