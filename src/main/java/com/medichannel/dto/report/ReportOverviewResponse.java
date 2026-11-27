package com.medichannel.dto.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Manager dashboard and reports. Every figure is computed by the backend
 * with SQL aggregate queries over the requested date range.
 */
public record ReportOverviewResponse(

        LocalDate from,
        LocalDate to,

        LocalDate sessionsWeekFrom,
        LocalDate sessionsWeekTo,
        long sessionsThisWeek,

        List<TodaySession> todaySessions,
        List<DailyAppointmentCount> appointmentsPerDay,

        long physicalAppointments,
        long onlineAppointments,
        long totalAppointments,
        long noShows,
        long cancelledAppointments,
        double noShowRate,

        BigDecimal revenueLkr,
        long completedPayments,

        List<DoctorPerformance> topDoctors,
        List<DoctorRating> doctorRatings,

        long completedAppointments,
        long bookedAppointments,
        long checkedInAppointments,
        long arrivedAppointments,
        long inConsultationAppointments,
        long unpaidAppointments,
        BigDecimal unpaidAmountLkr,
        long doctorsToday,
        long roomsToday,
        long sessionsToday,
        long activeSessionsToday,
        long completedSessionsToday,
        long cancelledSessionsToday,
        long totalCapacity,
        long bookedCapacity,
        double occupancyRate

) {

    public ReportOverviewResponse(
            LocalDate from,
            LocalDate to,
            LocalDate sessionsWeekFrom,
            LocalDate sessionsWeekTo,
            long sessionsThisWeek,
            List<TodaySession> todaySessions,
            List<DailyAppointmentCount> appointmentsPerDay,
            long physicalAppointments,
            long onlineAppointments,
            long totalAppointments,
            long noShows,
            long cancelledAppointments,
            double noShowRate,
            BigDecimal revenueLkr,
            long completedPayments,
            List<DoctorPerformance> topDoctors,
            List<DoctorRating> doctorRatings
    ) {
        this(
                from,
                to,
                sessionsWeekFrom,
                sessionsWeekTo,
                sessionsThisWeek,
                todaySessions,
                appointmentsPerDay,
                physicalAppointments,
                onlineAppointments,
                totalAppointments,
                noShows,
                cancelledAppointments,
                noShowRate,
                revenueLkr,
                completedPayments,
                topDoctors,
                doctorRatings,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0.0
        );
    }

    public record TodaySession(
            UUID sessionId,
            String doctorName,
            String specialisation,
            String roomNumber,
            LocalTime startTime,
            LocalTime endTime,
            String consultationType,
            String status,
            int maxAppointments,
            long booked,
            long completed,
            int delayMinutes
    ) {
        public TodaySession(
                UUID sessionId,
                String doctorName,
                String specialisation,
                String roomNumber,
                LocalTime startTime,
                LocalTime endTime,
                String consultationType,
                String status,
                int maxAppointments,
                long booked,
                long completed
        ) {
            this(
                    sessionId,
                    doctorName,
                    specialisation,
                    roomNumber,
                    startTime,
                    endTime,
                    consultationType,
                    status,
                    maxAppointments,
                    booked,
                    completed,
                    0
            );
        }
    }

    public record DailyAppointmentCount(
            LocalDate date,
            long appointments
    ) {
    }

    public record DoctorPerformance(
            UUID doctorId,
            String doctorName,
            long appointments,
            BigDecimal revenueLkr
    ) {
    }

    public record DoctorRating(
            UUID doctorId,
            String doctorName,
            double averageRating,
            long reviewCount
    ) {
    }
}
