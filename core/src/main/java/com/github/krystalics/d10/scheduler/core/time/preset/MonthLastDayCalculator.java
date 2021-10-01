package com.github.krystalics.d10.scheduler.core.time.preset;


import com.github.krystalics.d10.scheduler.core.time.preset.base.TimeParamCalculator;
import com.github.krystalics.d10.scheduler.core.time.utils.DateUtils;
import org.joda.time.DateTime;

/**
 * @description: 本月最后一天表达式获取
 * @author: yueyunyue
 * @create: 2018-07-15
 **/
public class MonthLastDayCalculator implements TimeParamCalculator {

    @Override
    public DateTime calculate(DateTime dateTime) {
        return dateTime.dayOfMonth().withMaximumValue();
    }

    @Override
    public String format(DateTime dateTime) {
        return dateTime.toString(DateUtils.COMMON_DATE_FORMAT);
    }
}
