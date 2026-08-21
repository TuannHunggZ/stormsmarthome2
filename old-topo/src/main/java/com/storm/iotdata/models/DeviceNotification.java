package com.storm.iotdata.models;

import com.google.gson.Gson;

/**
 * DeviceNotification là thông báo bất thường ở cấp thiết bị.
 *
 * Ý nghĩa field:
 * - `type`: loại cảnh báo (-1 thấp, 0 cao hơn avg, 1 vượt max theo logic hiện tại).
 * - `houseId`, `householdId`, `deviceId`: định danh nguồn phát sinh cảnh báo.
 * - `min/max/avg`: baseline lịch sử dùng để so sánh.
 * - `value`: giá trị thực tế tại timeslice gây cảnh báo.
 * - `timestamp`: thời điểm tạo notification.
 * - `saved`: cờ persist, hiện chủ yếu để tương thích model khác.
 */
public class DeviceNotification extends Timeslice{
    public Integer type;
    public Integer houseId;
    public Integer householdId;
    public Integer deviceId;
    public Double min;
    public Double max;
    public Double avg;
    public Double value;
    public Long timestamp;
    public Boolean saved=false;

    public DeviceNotification(Integer type, DeviceData data, DeviceProp dataProp){
        super(data.year, data.month, data.day, data.sliceIndex, data.sliceGap);
        this.type=type;
        // TODO: `houseId` đang được gán bằng `data.deviceId`, có thể là bug dữ liệu nếu notification này được dùng downstream.
        this.houseId=data.deviceId;
        this.householdId=data.householdId;
        this.deviceId=data.deviceId;
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

    public Integer getDeviceId() {
        return this.deviceId;
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
