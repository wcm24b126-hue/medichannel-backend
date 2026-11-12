package com.medichannel.dto.queue;

import java.util.List;
import java.util.UUID;

import com.medichannel.dto.doctor.DoctorAppointmentResponse;
import com.medichannel.enums.SessionStatus;

/**
 * Doctor-facing queue for one session. Appointments are always
 * ordered by their database allocated appointment number.
 */
public record SessionQueueResponse(

        UUID sessionId,
        Integer nowServing,
        Integer nextNumber,

        int remainingAppointments,
        int maxAppointments,

        SessionStatus status,

        List<DoctorAppointmentResponse> appointments

) {
}
