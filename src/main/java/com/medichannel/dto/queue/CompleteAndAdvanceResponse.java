package com.medichannel.dto.queue;

import com.medichannel.dto.doctor.DoctorAppointmentResponse;

/**
 * Result of completing a consultation and advancing the queue.
 *
 * next is the appointment that was started automatically. It is null
 * when nobody is waiting or when the next patient still has to pay.
 */
public record CompleteAndAdvanceResponse(

        DoctorAppointmentResponse completed,
        DoctorAppointmentResponse next,
        boolean paymentRequired,
        SessionQueueResponse queue

) {
}
