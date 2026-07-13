package com.storm.iotdata.models;

import java.io.Serializable;
import java.util.Objects;

/**
 * HouseholdProp lưu thống kê lịch sử ở cấp household để kiểm tra outlier.
 *
 * Ý nghĩa field:
 * - `houseId`, `householdId`: định danh household.
 * - `sliceGap`: window áp dụng.
 * - `min/max/avg/count`: rolling statistics.
 * - `lastUpdate`: cập nhật cuối.
 * - `saved`: đã persist xuống DB hay chưa.
 *
 * Các khóa:
 * - `getUniqueId()`: household + gap.
 * - `getHouseholdUniqueId()`: household không kèm gap, dùng làm key map trong bolt.
 *
 * Tóm tắt:
 * - State lịch sử của `Bolt_sum` ở cấp household.
 * - Không phải dữ liệu stream; chỉ hỗ trợ persist và outlier detection.
 */
public class HouseholdProp implements Serializable {
    // House cha.
    public int houseId;
    // Household con.
    public int householdId;
    // Window/gap của thống kê.
    public int sliceGap;
    // Giá trị min lịch sử.
    public Double min;
    // Giá trị max lịch sử.
    public Double max;
    // Giá trị avg lịch sử.
    public Double avg;
    // Số mẫu đã góp vào rolling statistics.
    public Double count;
    // Mốc cập nhật gần nhất.
    public Long lastUpdate;
    // Đã lưu DB hay chưa.
    public boolean saved = false;

    public HouseholdProp(int houseId, int householdId, int sliceGap) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.sliceGap = sliceGap;
        this.min = Double.valueOf(0);
        this.max = Double.valueOf(0);
        this.avg = Double.valueOf(0);
        this.count = Double.valueOf(0);
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public HouseholdProp(int houseId, int householdId, int sliceGap, Double min, Double max, Double avg) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.sliceGap = sliceGap;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.count = Double.valueOf(1);
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public HouseholdProp(int houseId, int householdId, int sliceGap, Double min, Double max, Double avg, Double count) {
        this.houseId = houseId;
        this.householdId = householdId;
        this.sliceGap = sliceGap;
        this.min = min;
        this.max = max;
        this.avg = avg;
        this.count = count;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
    }

    public HouseholdProp(int houseId, int householdId, int sliceGap, Double min, Double max, Double avg, Double count, boolean saved) {
        this.houseId = houseId;
        this.householdId = householdId;
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

    public int getSliceGap() {
        return this.sliceGap;
    }

    public void setSliceGap(int sliceGap) {
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

    public void setLastUpdate() {
        this.lastUpdate = System.currentTimeMillis();
    }

    public boolean isSaved() {
        return this.saved;
    }

    public HouseholdProp houseId(int houseId) {
        this.houseId = houseId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp householdId(int householdId) {
        this.householdId = householdId;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp sliceGap(int sliceGap) {
        this.sliceGap = sliceGap;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp min(Double min) {
        this.min = min;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp max(Double max) {
        this.max = max;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp avg(Double avg) {
        this.avg = avg;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp count(Double count) {
        this.count = count;
        this.lastUpdate = System.currentTimeMillis();
        this.saved = false;
        return this;
    }

    public HouseholdProp lastUpdate() {
        this.lastUpdate = System.currentTimeMillis();
        return this;
    }

    public HouseholdProp save() {
        this.saved = true;
        return this;
    }

    public HouseholdProp addValue(Double value) {
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

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof HouseholdProp)) {
            return false;
        }
        HouseholdProp householdProp = (HouseholdProp) o;
        return houseId == householdProp.houseId && householdId == householdProp.householdId && sliceGap == householdProp.sliceGap && Objects.equals(min, householdProp.min) && Objects.equals(max, householdProp.max) && Objects.equals(avg, householdProp.avg) && Objects.equals(count, householdProp.count) && Objects.equals(lastUpdate, householdProp.lastUpdate) && saved == householdProp.saved;
    }

    @Override
    public int hashCode() {
        return Objects.hash(houseId, householdId, sliceGap, min, max, avg, count, lastUpdate, saved);
    }

    @Override
    public String toString() {
        return "{" +
            " houseId='" + getHouseId() + "'" +
            ", householdId='" + getHouseholdId() + "'" +
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
        return String.format("%d-%d-%d", houseId, householdId, sliceGap);
    }
    
    public String getHouseholdUniqueId() {
        return String.format("%d-%d", houseId, householdId);
    }

}
