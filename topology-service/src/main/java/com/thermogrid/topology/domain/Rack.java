package com.thermogrid.topology.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Neighbours are not stored: grid coordinates (row_index, col_index) define adjacency. */
@Entity
@Table(name = "rack", uniqueConstraints = @UniqueConstraint(name = "uk_rack_row_col", columnNames = {"row_index", "col_index"}))
public class Rack {

    @Id
    @Column(name = "id", length = 8, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rack_zone"))
    private CoolingZone zone;

    @Column(name = "row_index")
    private int rowIndex;

    @Column(name = "col_index")
    private int colIndex;

    @Column(name = "cpu_capacity")
    @ColumnDefault("100")
    private int cpuCapacity = 100;

    @Column(name = "idle_power_kw", precision = 4, scale = 2)
    @ColumnDefault("2.00")
    private BigDecimal idlePowerKw = new BigDecimal("2.00");

    @Column(name = "max_power_kw", precision = 4, scale = 2)
    @ColumnDefault("10.00")
    private BigDecimal maxPowerKw = new BigDecimal("10.00");

    // VARCHAR(12), not a native MySQL enum column
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 12)
    @ColumnDefault("'ACTIVE'")
    private RackStatus status = RackStatus.ACTIVE;

    protected Rack() {
    }

    public Rack(String id, CoolingZone zone, int rowIndex, int colIndex) {
        this.id = id;
        this.zone = zone;
        this.rowIndex = rowIndex;
        this.colIndex = colIndex;
    }

    public String getId() { return id; }
    public CoolingZone getZone() { return zone; }
    public int getRowIndex() { return rowIndex; }
    public int getColIndex() { return colIndex; }
    public int getCpuCapacity() { return cpuCapacity; }
    public BigDecimal getIdlePowerKw() { return idlePowerKw; }
    public BigDecimal getMaxPowerKw() { return maxPowerKw; }
    public RackStatus getStatus() { return status; }
    public void setStatus(RackStatus status) { this.status = status; }
}