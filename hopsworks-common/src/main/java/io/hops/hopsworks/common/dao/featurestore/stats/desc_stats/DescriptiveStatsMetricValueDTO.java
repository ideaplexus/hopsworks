/*
 * This file is part of Hopsworks
 * Copyright (C) 2018, Logical Clocks AB. All rights reserved
 *
 * Hopsworks is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Hopsworks is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.hops.hopsworks.common.dao.featurestore.stats.desc_stats;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

/**
 * XML/JSON DTO representing a single descriptive statistic metric and value
 */
@XmlRootElement
@XmlType(propOrder = {"metricName", "value"})
public class DescriptiveStatsMetricValueDTO {

  private String metricName;
  private Float value;

  public DescriptiveStatsMetricValueDTO() {
    //Default empty constructor required for JAXB
  }

  @XmlElement
  public String getMetricName() {
    return metricName;
  }

  @XmlElement
  public Float getValue() {
    return value;
  }

  public void setMetricName(String metricName) {
    this.metricName = metricName;
  }

  public void setValue(Float value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return "DescriptiveStatsMetricValueDTO{" +
        "metricName='" + metricName + '\'' +
        ", value=" + value +
        '}';
  }
}
