package com.storm.iotdata.models;

import java.io.Serializable;
import java.util.Objects;

/**
 * DeviceData
 */

/**
 * DeviceData là aggregate ở cấp thiết bị cho một `Timeslice`.
 *
 * Ý nghĩa field:
 * - `houseId`, `householdId`, `deviceId`: định danh vị trí thiết bị trong hệ thống.
 * - `value`: tổng giá trị đã cộng dồn trong timeslice hiện tại.
 * - `count`: số mẫu đã góp vào `value`.
 * - `lastUpdate`: thời điểm object được cập nhật gần nhất, dùng để dọn cache.
 * - `saved`: đánh dấu record đã được persist xuống DB hay chưa.
 *
 * Các khóa:
 * - `getUniqueId()`: khóa đầy đủ theo `house-household-device-timeslice`, dùng làm key cache chính trong `Bolt_avg`.
 * - `getDeviceUniqueId()`: khóa thiết bị không kèm timeslice, dùng làm key thống kê lịch sử `DeviceProp`.
 * - `getHouseholdUniqueId()`: khóa household cha của thiết bị.
 *
 * Tóm tắt:
 * - Đây là object trung gian quan trọng nhất ở tầng device.
 * - Không tự ghi DB; được `Bolt_avg` quản lý vòng đời.
 * - Có thể dùng song song an toàn vì là data object.
 * - TODO: class hiện để field public nên rất dễ bị sửa state trực tiếp ngoài các setter/builder method.
 */
public class DeviceData extends Timeslice implements Serializable {

    // House chứa thiết bị.
    public Integer houseId;
    // Household chứa thiết bị.
    public Integer householdId;
    // Định danh thiết bị trong household.
    public Integer deviceId;
    // Tổng giá trị cộng dồn trong timeslice.
    public Double value;
    // Số lượng mẫu đã cộng để tính trung bình.
    public Double count;
    // Mốc cập nhật cuối, dùng cho chiến lược cleanup state.
    public Long lastUpdate;
    // Đánh dấu đã ghi DB hay chưa.
    public Boolean saved=false;

    public DeviceData() {
        super();
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, Timeslice timeslice, Double value, Double count) {
        super(timeslice);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=value;
        this.count=count;
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, Timeslice timeslice, Double avg) {
        super(timeslice);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=avg;
        this.count=Double.valueOf(1);
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, Timeslice timeslice, Double value, Double count, Boolean saved) {
        super(timeslice);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=value;
        this.count=count;
        this.lastUpdate=System.currentTimeMillis();
        this.saved=saved;
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, String year, String month, String day, Integer sliceIndex, Integer sliceGap, Double avg) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=avg;
        this.count=Double.valueOf(1);
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, String year, String month, String day, Integer sliceIndex, Integer sliceGap, Double value, Double count, Boolean saved) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=value;
        this.count=count;
        this.lastUpdate=System.currentTimeMillis();
        this.saved=saved;
    }

    public DeviceData(Integer houseId, Integer householdId, Integer deviceId, String year, String month, String day, Integer sliceIndex, Integer sliceGap) {
        super(year, month, day, sliceIndex, sliceGap);
        this.houseId=houseId;
        this.householdId=householdId;
        this.deviceId=deviceId;
        this.value=Double.valueOf(0);
        this.count=Double.valueOf(0);
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
    }

    public Integer getHouseId() {
        return this.houseId;
    }

    public void setHouseId(Integer houseId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.houseId=houseId;
    }

    public Integer getHouseholdId() {
        return this.householdId;
    }

    public void setHouseholdId(Integer householdId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.householdId=householdId;
    }

    public Integer getDeviceId() {
        return this.deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.deviceId=deviceId;
    }

    public DeviceData avg(Double avg) {
        this.lastUpdate=System.currentTimeMillis();
        this.count=Double.valueOf(1);
        this.value=avg;
        return this;
    }

    public Double getValue() {
        return this.value;
    }

    public void setValue(Double value) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.value=value;
    }

    public Double getCount() {
        return this.count;
    }

    public void setCount(Double count) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.count=count;
    }

    public long getLastUpdate() {
        return this.lastUpdate;
    }

    public Double getAvg() {
        // Trung bình timeslice = tổng giá trị / số mẫu đã tích lũy.
        if(this.count==0){
            return  Double.valueOf(0);
        }
        return this.value/this.count;
    }

    public DeviceData houseId(Integer houseId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.houseId=houseId;
        return this;
    }

    public DeviceData householdId(Integer householdId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.householdId=householdId;
        return this;
    }

    public DeviceData deviceId(Integer deviceId) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.deviceId=deviceId;
        return this;
    }

    public DeviceData saved(Boolean saved) {
        this.saved=saved;
        return this;
    }

    public Boolean isSaved() {
        return this.saved;
    }

    public DeviceData save() {
        this.saved=true;
        return this;
    }

    public DeviceData value(Double value) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.value=value;
        return this;
    }

    public DeviceData increaseValue(Double value){
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.value+=value;
        this.count++;
        return this;
    }

    public DeviceData count(Double count) {
        this.lastUpdate=System.currentTimeMillis();
        this.saved=false;
        this.count=count;
        return this;
    }

    @Override
    public String toString() {
        return "{" +
            " houseId='" + getHouseId() + "'" +
            ", householdId='" + getHouseholdId() + "'" +
            ", deviceId='" + getDeviceId() + "'" +
            ", year='" + getYear() + "'" +
            ", month='" + getMonth() + "'" +
            ", day='" + getDay() + "'" +
            ", sliceIndex='" + getIndex() + "'" +
            ", sliceGap='" + getGap() + "'" +
            ", value='" + getValue() + "'" +
            ", count='" + getCount() + "'" +
            ", lastUpdate='" + getLastUpdate() + "'" +
            ", saved='" + isSaved() + "'" +
            "}";
    }

    public String getUniqueId(){
        // Khóa aggregate đầy đủ của một device trong một timeslice.
        return String.format("%d-%d-%d-%s-%s-%s-%d-%d", houseId, householdId, deviceId, year, month, day, sliceGap, sliceIndex);
    }

	public String getDeviceUniqueId() {
        // Khóa thiết bị ổn định qua mọi timeslice, dùng để lookup `DeviceProp`.
		return String.format("%d-%d-%d", houseId, householdId, deviceId);
    }
    
    public String getHouseholdUniqueId() {
        return String.format("%d-%d", houseId, householdId);
    }
    
}