package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.MeProfileResponse;
import com.crm.travelcrm.auth.dto.UpdateMeProfileRequest;
import com.crm.travelcrm.auth.entity.User;

/** Self-service profile for the authenticated tenant user (view + edit own name/phone). */
public interface MeProfileService {

    MeProfileResponse getMyProfile(User principal);

    MeProfileResponse updateMyProfile(User principal, UpdateMeProfileRequest request);
}