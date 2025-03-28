/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RangerValidityRecurrence implements Serializable {
    private RecurrenceSchedule schedule;
    private ValidityInterval   interval;

    public RangerValidityRecurrence() {
    }

    public RangerValidityRecurrence(RecurrenceSchedule schedule, ValidityInterval interval) {
        setSchedule(schedule);
        setInterval(interval);
    }

    public RecurrenceSchedule getSchedule() {
        return schedule;
    }

    public void setSchedule(RecurrenceSchedule schedule) {
        this.schedule = schedule;
    }

    public ValidityInterval getInterval() {
        return interval;
    }

    public void setInterval(ValidityInterval interval) {
        this.interval = interval;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("{RangerValidityRecurrence= {");

        sb.append(schedule).append(interval);
        sb.append(" }");

        return sb.toString();
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidityInterval {
        private final int days;
        private final int hours;
        private final int minutes;

        public ValidityInterval() {
            this.days    = 0;
            this.hours   = 0;
            this.minutes = 0;
        }

        public ValidityInterval(int days, int hours, int minutes) {
            this.days    = days;
            this.hours   = hours;
            this.minutes = minutes;
        }

        public static int getValidityIntervalInMinutes(ValidityInterval interval) {
            return interval != null ? (interval.getDays() * 24 + interval.getHours()) * 60 + interval.getMinutes() : 0;
        }

        public int getDays() {
            return days;
        }

        public int getHours() {
            return hours;
        }

        public int getMinutes() {
            return minutes;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ValidityInterval={");

            sb.append(" Interval={");
            sb.append("days=").append(days);
            sb.append(", hours=").append(hours);
            sb.append(", minutes=").append(minutes);
            sb.append(" }");

            return sb.toString();
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecurrenceSchedule {
        public static final String WILDCARD = "*";

        static final String PERMITTED_SPECIAL_CHARACTERS             = "*,-";
        static final String PERMITTED_SPECIAL_CHARACTERS_FOR_MINUTES = ",";

        private String minute;
        private String hour;
        private String dayOfMonth;
        private String dayOfWeek;
        private String month;
        private String year;

        public RecurrenceSchedule() {}

        public RecurrenceSchedule(String minute, String hour, String dayOfMonth, String dayOfWeek, String month, String year) {
            setMinute(minute);
            setHour(hour);
            setDayOfMonth(dayOfMonth);
            setDayOfWeek(dayOfWeek);
            setMonth(month);
            setYear(year);
        }

        public String getMinute() {
            return minute;
        }

        public void setMinute(String minute) {
            this.minute = minute;
        }

        public String getHour() {
            return hour;
        }

        public void setHour(String hour) {
            this.hour = hour;
        }

        public String getDayOfMonth() {
            return dayOfMonth;
        }

        public void setDayOfMonth(String dayOfMonth) {
            this.dayOfMonth = dayOfMonth;
        }

        public String getDayOfWeek() {
            return dayOfWeek;
        }

        public void setDayOfWeek(String dayOfWeek) {
            this.dayOfWeek = dayOfWeek;
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public String getYear() {
            return year;
        }

        public void setYear(String year) {
            this.year = year;
        }

        public void setFieldValue(ScheduleFieldSpec field, String value) {
            switch (field) {
                case minute:
                    setMinute(value);
                    break;
                case hour:
                    setHour(value);
                    break;
                case dayOfMonth:
                    setDayOfMonth(value);
                    break;
                case dayOfWeek:
                    setDayOfWeek(value);
                    break;
                case month:
                    setMonth(value);
                    break;
                case year:
                    setYear(value);
                    break;
                default:
                    break;
            }
        }

        public String getFieldValue(ScheduleFieldSpec field) {
            switch (field) {
                case minute:
                    return getMinute();
                case hour:
                    return getHour();
                case dayOfMonth:
                    return getDayOfMonth();
                case dayOfWeek:
                    return getDayOfWeek();
                case month:
                    return getMonth();
                case year:
                    return getYear();
                default:
                    return null;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(" Schedule={");

            sb.append(" minute=").append(minute);
            sb.append(", hour=").append(hour);
            sb.append(", dayOfMonth=").append(dayOfMonth);
            sb.append(", dayOfWeek=").append(dayOfWeek);
            sb.append(", month=").append(month);
            sb.append(", year=").append(year);
            sb.append(" }");

            return sb.toString();
        }

        public enum ScheduleFieldSpec {
            minute(0, 59, PERMITTED_SPECIAL_CHARACTERS_FOR_MINUTES),
            hour(0, 23, PERMITTED_SPECIAL_CHARACTERS),
            dayOfMonth(1, 31, PERMITTED_SPECIAL_CHARACTERS),
            dayOfWeek(1, 7, PERMITTED_SPECIAL_CHARACTERS),
            month(0, 11, PERMITTED_SPECIAL_CHARACTERS),
            year(2017, 2100, PERMITTED_SPECIAL_CHARACTERS);

            public final int    minimum;
            public final int    maximum;
            public final String specialChars;

            ScheduleFieldSpec(int minimum, int maximum, String specialChars) {
                this.minimum      = minimum;
                this.maximum      = maximum;
                this.specialChars = specialChars;
            }
        }
    }
}
