package com.crm.travelcrm.otp.delivery;

import com.crm.travelcrm.otp.OtpChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Factory that routes to the right {@link OtpDeliverySender} for a channel. It auto-discovers every
 * sender bean, so registering a new channel implementation wires it in with no edits here. {@link
 * OtpChannel#AUTO} infers EMAIL when the destination looks like an email, otherwise SMS.
 */
@Component
public class OtpSenderResolver {

    private final Map<OtpChannel, OtpDeliverySender> byChannel = new EnumMap<>(OtpChannel.class);

    public OtpSenderResolver(List<OtpDeliverySender> senders) {
        for (OtpDeliverySender sender : senders) {
            byChannel.put(sender.channel(), sender);
        }
    }

    public OtpDeliverySender resolve(OtpChannel requested, String destination) {
        OtpChannel channel = (requested == null || requested == OtpChannel.AUTO)
                ? inferFromDestination(destination)
                : requested;
        OtpDeliverySender sender = byChannel.get(channel);
        if (sender == null) {
            throw new IllegalStateException("No OTP sender registered for channel " + channel);
        }
        return sender;
    }

    private OtpChannel inferFromDestination(String destination) {
        return destination != null && destination.contains("@") ? OtpChannel.EMAIL : OtpChannel.SMS;
    }
}
