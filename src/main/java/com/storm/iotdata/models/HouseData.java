package com.storm.iotdata.models;

import java.io.Serializable;
import java.util.Objects;

/**
 * HouseData
 */
/**
 * HouseData là aggregate ở cấp house cho một `Timeslice`.
 *
 * Ý nghĩa field:
 * - `houseId`: định danh house.
 * - `value`: tổng hoặc trung bình đang được lưu cho house ở timeslice đó.
 * - `lastUpdate`: thời điểm cập nhật cuối để cleanup cache.
 * - `saved`: đã persist xuống DB hay chưa.
 *
 * Các khóa:
 * - `getUniqueId()`: house + timeslice, dùng làm khóa cache và DB.
 * - `getHouseUniqueId()`: chỉ định danh house, dùng làm khóa thống kê `HouseProp`.
 *
 * Tóm tắt:
 * - Object này là đầu ra aggregate của `Bolt_sum` và đầu vào forecast.
 * - State vòng đời được quản lý bởi `Bolt_sum`/`Bolt_forecast`.
 * - Có thể dùng song song an toàn vì là data object.
 */
public class HouseData extends Timeslice implements Serializable{

    // Định danh house.
    public Integer houseId;
    // Giá trị aggregate của house trong timeslice.
    public Double value;
    // Mốc cập nhật gần nhất.
    public Long lastUpdate;
    // Đã lưu DB hay chưa.
    public Boolean saved = false;

    public HouseData() {
        super();
    }

    public HouseData(Integer houseId, Timeslice timeslice, Double value) {
        super(timeslice);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = false;
    }

    public HouseData(Integer houseId, Timeslice timeslice, Double value, Boolean saved) {
        super(timeslice);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = saved;
    }

    public HouseData(Integer houseId, String timeslice, Double value) {
        super(timeslice);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = false;
    }

    public HouseData(Integer houseId, String timeslice, Double value, Boolean saved) {
        super(timeslice);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = saved;
    }

    public HouseData(Integer houseId, String year, String month, String day, Integer sliceIndex, Integer sliceGap, Double value) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = false;
    }

    public HouseData(Integer houseId, String year, String month, String day, Integer sliceIndex, Integer sliceGap, Double value, Long lastUpdate, Boolean saved) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId = houseId;
        this.value = value;
        this.setLastUpdate();
        this.saved = saved;
    }

    public HouseData(Integer houseId, String year, String month, String day, Integer sliceIndex, Integer sliceGap) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId = houseId;
        this.value = Double.valueOf(0);
        this.setLastUpdate();
        this.saved = false;
    }

    public Integer getHouseId() {
        return this.houseId;
    }

    public void setHouseId(Integer houseId) {
        if(this.houseId != houseId){
            this.setLastUpdate();
            this.saved = false;
            this.houseId = houseId;
        }
    }

    public Double getValue() {
        return this.value;
    }

    public void setValue(Double value) {
        if(!this.value.equals(value)){
            this.setLastUpdate();
            this.saved = false;
            this.value = value;
        }
    }

    public Double getAvg() {
        return getValue();
    }

    public Long getLastUpdate() {
        return this.lastUpdate;
    }

    public Boolean isSaved() {
        return this.saved;
    }

    public HouseData save() {
        this.saved = true;
        return this;
    }

    public HouseData houseId(Integer houseId) {
        this.setHouseId(houseId);
        return this;
    }

    public HouseData value(Double value) {
        this.setValue(value);
        return this;
    }
    
    public void setLastUpdate() {
        this.lastUpdate = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "{" +
            " houseId='" + getHouseId() + "'" +
            ", year='" + getYear() + "'" +
            ", month='" + getMonth() + "'" +
            ", day='" + getDay() + "'" +
            ", sliceIndex='" + getIndex() + "'" +
            ", sliceGap='" + getGap() + "'" +
            ", value='" + getValue() + "'" +
            ", lastUpdate='" + getLastUpdate() + "'" +
            ", saved='" + isSaved() + "'" +
            "}";
    }

    public String getUniqueId() {
        // Khóa đầy đủ của house theo timeslice.
        return String.format("%d-%s-%s-%s-%d-%d", houseId, year, month, day, sliceGap, sliceIndex);
    }

    public String getHouseUniqueId() {
        return String.valueOf(getHouseId());
    }

}
