package com.storm.iotdata.models;

import java.io.Serializable;

/**
 * DeviceProp lưu thống kê lịch sử theo thiết bị để so sánh và phát hiện bất thường.
 *
 * Ý nghĩa field:
 * - `houseId`, `householdId`, `deviceId`: định danh thiết bị.
 * - `sliceGap`: window mà thống kê này áp dụng.
 * - `min/max/avg/count`: thống kê lịch sử rolling.
 * - `lastUpdate`: thời điểm cập nhật cuối.
 * - `saved`: đã persist xuống DB hay chưa.
 *
 * Các khóa:
 * - `getUniqueId()`: device + gap, dùng làm key bản ghi thống kê cụ thể.
 * - `getDeviceUniqueId()`: device không kèm gap, hay được dùng để lookup nhanh trong bolt.
 *
 * Tóm tắt:
 * - Object này là state lâu dài của `Bolt_avg`.
 * - Không phải dữ liệu stream; chỉ hỗ trợ outlier detection.
 * - Có thể dùng song song an toàn vì là data object, nhưng cần chú ý nhất quán khi nhiều instance cùng ghi.
 */
public class DeviceProp implements Serializable{
    // House chứa thiết bị.
    public int houseId;
    // Household chứa thiết bị.
    public int householdId;
    // Định danh thiết bị.
    public int deviceId;
    // Gap/window của thống kê này.
    public int sliceGap;
    // Giá trị nhỏ nhất lịch sử.
    public Double min;
    // Giá trị lớn nhất lịch sử.
    public Double max;
    // Giá trị trung bình lịch sử.
    public Double avg;
    // Số lượng mẫu đã góp vào rolling statistic.
    public Double count;
    // Mốc cập nhật gần nhất.
    public Long lastUpdate;
    // Đã lưu DB hay chưa.
    public boolean saved = false;

    public DeviceProp(int houseId, int householdId, int deviceId, int sliceGap) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.deviceId = deviceId;
        this.sliceGap = sliceGap;
        this.min = Double.valueOf(0);
        this.max = Double.valueOf(0);
        this.count = Double.valueOf(0);
        this.avg = Double.valueOf(0);
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public DeviceProp(int houseId, int householdId, int deviceId, int sliceGap, Double min, Double avg, Double max, Double count) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.deviceId = deviceId;
        this.sliceGap = sliceGap;
        this.min = min;
        this.max = max;
        this.count = count;
        this.avg = avg;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public DeviceProp(int houseId, int householdId, int deviceId, int sliceGap, Double min, Double avg, Double max) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.deviceId = deviceId;
        this.sliceGap = sliceGap;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.count = Double.valueOf(1);
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public DeviceProp(int houseId, int householdId, int deviceId, int sliceGap, Double min, Double avg, Double max, Double count, Boolean saved) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.deviceId = deviceId;
        this.sliceGap = sliceGap;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.count = count;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = saved;
    }

    public int getHouseId() {
        return this.houseId;
    }

    public void setHouseId(int houseId) {
        this.houseId = houseId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public int getHouseholdId() {
        return this.householdId;
    }

    public void setHouseholdId(int householdId) {
        this.householdId = householdId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public int getDeviceId() {
        return this.deviceId;
    }

    public void setDeviceId(int deviceId) {
        this.deviceId = deviceId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public int getSliceGap() {
        // TODO: method hiện trả về `deviceId` thay vì `sliceGap`; comment giữ nguyên để không đổi logic.
        return this.deviceId;
    }

    public void setWindows(int sliceGap) {
        this.sliceGap = sliceGap;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public Double getMin() {
        return this.min;
    }

    public void setMin(Double min) {
        this.min = min;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public Double getMax() {
        return this.max;
    }

    public void setMax(Double max) {
        this.max = max;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public Double getAvg() {
        return this.avg;
    }

    public void setAvg(Double avg) {
        this.avg = avg;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public Double getCount() {
        return this.count;
    }

    public void setCount(Double count) {
        this.count = count;
    }

    public Long getLastUpdate() {
        return this.lastUpdate;
    }

    public void setLastUpdate(Long lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public boolean isSaved() {
        return this.saved;
    }

    public void save() {
        this.saved = true;
    }

    public DeviceProp houseId(int houseId) {
        this.houseId = houseId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp householdId(int householdId) {
        this.householdId = householdId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp deviceId(int deviceId) {
        this.deviceId = deviceId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp sliceGap(int sliceGap) {
        this.sliceGap = sliceGap;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp min(Double min) {
        this.min = min;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp max(Double max) {
        this.max = max;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp avg(Double avg) {
        this.avg = avg;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public DeviceProp addValue(Double value) {
        if(value != 0){
            this.avg = (this.avg*this.count + value)/(++this.count);
            if(value<this.getMin()){
                this.setMin(value);
            }
            else if(value>this.getMax()){
                this.setMax(value);
            }
        }
        return this;
    }

    public DeviceProp lastUpdate() {
        this.lastUpdate = System.currentTimeMillis();
        return this;
    }

    public DeviceProp saved(boolean saved) {
        this.saved = saved;
        return this;
    }

    @Override
    public String toString() {
        return "{" +
            " houseId='" + getHouseId() + "'" +
            ", householdId='" + getHouseholdId() + "'" +
            ", deviceId='" + getDeviceId() + "'" +
            ", sliceGap='" + getSliceGap() + "'" +
            ", min='" + getMin() + "'" +
            ", max='" + getMax() + "'" +
            ", avg='" + getAvg() + "'" +
            ", count='" + getCount() + "'" +
            ", lastUpdate='" + getLastUpdate() + "'" +
            ", saved='" + isSaved() + "'" +
            "}";
    }

    public String getUniqueId(){
        // Khóa thống kê đầy đủ theo device và window.
        return String.format("%d-%d-%d-%d", houseId, householdId, deviceId, sliceGap);
    }

    public String getDeviceUniqueId(){
        return String.format("%d-%d-%d", houseId, householdId, deviceId);
    }
}