package com.thermogrid.topology.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "cooling_zone")
public class CoolingZone {

    @Id
    @Column(name = "id", length = 8, nullable = false)
    private String id;

    @Column(name = "name", length = 40)
    private String name;

    @Column(name = "row_index")
    private int rowIndex;

    @Column(name = "target_temp_c", precision = 4, scale = 1)
    @ColumnDefault("26.0")
    private BigDecimal targetTempC = new BigDecimal("26.0");

    protected CoolingZone() {
    }

    public CoolingZone(String id, String name, int rowIndex) {
        this.id = id;
        this.name = name;
        this.rowIndex = rowIndex;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getRowIndex() { return rowIndex; }
    public BigDecimal getTargetTempC() { return targetTempC; }
    public void setTargetTempC(BigDecimal targetTempC) { this.targetTempC = targetTempC; }
}