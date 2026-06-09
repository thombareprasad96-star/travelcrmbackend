//package com.crm.travelcrm.otp.factory;
//
//import com.crm.travelcrm.auth.enums.OtpChannel;
//import com.crm.travelcrm.otp.strategy.OtpStrategy;
//import org.springframework.stereotype.Component;
//
//import java.util.Map;
//
//@Component
//public class OtpStrategyFactory {
//
//    private final Map<String, OtpStrategy> strategyMap;
//
//    public OtpStrategyFactory(Map<String, OtpStrategy> strategyMap) {
//        this.strategyMap = strategyMap;
//    }
//
//    public OtpStrategy getStrategy(OtpChannel channel) {
//        OtpStrategy strategy = strategyMap.get(channel.name());
//
//        if (strategy == null) {
//            throw new IllegalArgumentException("No strategy found for: " + channel);
//        }
//
//        return strategy;
//    }
//}