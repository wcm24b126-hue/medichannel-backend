package com.medichannel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medichannel.dto.report.ReportOverviewResponse;
import com.medichannel.dto.report.ReportOverviewResponse.DailyAppointmentCount;
import com.medichannel.dto.report.ReportOverviewResponse.DoctorPerformance;
import com.medichannel.dto.report.ReportOverviewResponse.DoctorRating;
import com.medichannel.dto.report.ReportOverviewResponse.TodaySession;

@Service
public class ReportService {

    private static final int MAX_RANGE_DAYS = 366;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReportOverviewResponse overview(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        LocalDate rangeEnd = to == null ? today : to;
        LocalDate rangeStart = from == null
                ? rangeEnd.minusDays(29)
                : from;

        if (rangeStart.isAfter(rangeEnd)) {
            throw new IllegalArgumentException(
                    "The report start date must not be after the end date."
            );
        }
        if (rangeStart.plusDays(MAX_RANGE_DAYS).isBefore(rangeEnd)) {
            throw new IllegalArgumentException(
                    "The report range must not exceed " + MAX_RANGE_DAYS
                            + " days."
            );
        }

        LocalDate sessionsWeekFrom = today
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sessionsWeekTo = sessionsWeekFrom.plusDays(6);
        long sessionsThisWeek = countSessionsThisWeek(
                sessionsWeekFrom,
                sessionsWeekTo
        );

        List<TodaySession> todaySessions = todaySessions(today);
        List<DailyAppointmentCount> perDay =
                appointmentsPerDay(rangeStart, rangeEnd);

        AppointmentMetrics apptMetrics = appointmentMetrics(rangeStart, rangeEnd);

        long eligibleNoShow = apptMetrics.total - apptMetrics.cancelled;
        double noShowRate = eligibleNoShow <= 0
                ? 0.0
                : round(apptMetrics.noShows * 100.0 / eligibleNoShow, 2);

        BigDecimal revenue = revenue(rangeStart, rangeEnd);
        long completedPayments = completedPayments(rangeStart, rangeEnd);

        SessionTodayMetrics sessionMetrics = sessionTodayMetrics(today);

        return new ReportOverviewResponse(
                rangeStart,
                rangeEnd,
                sessionsWeekFrom,
                sessionsWeekTo,
                sessionsThisWeek,
                todaySessions,
                perDay,
                apptMetrics.physical,
                apptMetrics.online,
                apptMetrics.total,
                apptMetrics.noShows,
                apptMetrics.cancelled,
                noShowRate,
                revenue,
                completedPayments,
                topDoctors(rangeStart, rangeEnd),
                doctorRatings(),
                apptMetrics.completed,
                apptMetrics.booked,
                apptMetrics.checkedIn,
                apptMetrics.arrived,
                apptMetrics.inConsultation,
                apptMetrics.unpaid,
                apptMetrics.unpaidAmount,
                sessionMetrics.doctorsToday,
                sessionMetrics.roomsToday,
                sessionMetrics.sessionsToday,
                sessionMetrics.activeSessionsToday,
                sessionMetrics.completedSessionsToday,
                sessionMetrics.cancelledSessionsToday,
                sessionMetrics.totalCapacity,
                sessionMetrics.bookedCapacity,
                sessionMetrics.occupancyRate
        );
    }

    /**
     * The brief states that the hospital runs more than 120 channelling
     * sessions a week. This count is deliberately independent of the report's
     * from/to range so the dashboard can show the real weekly roster while
     * keeping its other figures scoped to today.
     */
    private long countSessionsThisWeek(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(*)
                FROM doctor_sessions
                WHERE session_date BETWEEN ? AND ?
                  AND status <> 'CANCELLED'
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                Date.valueOf(from),
                Date.valueOf(to)
        );
        return count == null ? 0L : count;
    }

    private List<TodaySession> todaySessions(LocalDate today) {
        String sql = """
                SELECT s.id,
                       d.first_name || ' ' || d.last_name AS doctor_name,
                       sp.name AS specialisation,
                       r.room_number,
                       s.start_time,
                       s.end_time,
                       s.consultation_type,
                       s.status,
                       s.max_appointments,
                       COALESCE(s.delay_minutes, 0) AS delay_minutes,
                       COUNT(a.id) FILTER (
                           WHERE a.status <> 'CANCELLED'
                       ) AS booked,
                       COUNT(a.id) FILTER (
                           WHERE a.status = 'COMPLETED'
                       ) AS completed
                FROM doctor_sessions s
                JOIN doctors d ON d.id = s.doctor_id
                JOIN specialisations sp ON sp.id = d.specialisation_id
                LEFT JOIN rooms r ON r.id = s.room_id
                LEFT JOIN appointments a ON a.session_id = s.id
                WHERE s.session_date = ?
                GROUP BY s.id, d.first_name, d.last_name, sp.name,
                         r.room_number, s.start_time, s.end_time,
                         s.consultation_type, s.status, s.max_appointments,
                         s.delay_minutes
                ORDER BY s.start_time
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new TodaySession(
                        rs.getObject("id", UUID.class),
                        rs.getString("doctor_name"),
                        rs.getString("specialisation"),
                        rs.getString("room_number"),
                        toLocalTime(rs.getTime("start_time")),
                        toLocalTime(rs.getTime("end_time")),
                        rs.getString("consultation_type"),
                        rs.getString("status"),
                        rs.getInt("max_appointments"),
                        rs.getLong("booked"),
                        rs.getLong("completed"),
                        rs.getInt("delay_minutes")
                ),
                Date.valueOf(today)
        );
    }

    private List<DailyAppointmentCount> appointmentsPerDay(
            LocalDate from,
            LocalDate to
    ) {
        String sql = """
                SELECT s.session_date AS day, COUNT(a.id) AS appointments
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE s.session_date BETWEEN ? AND ?
                GROUP BY s.session_date
                ORDER BY s.session_date
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DailyAppointmentCount(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("appointments")
                ),
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }

    private AppointmentMetrics appointmentMetrics(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(a.id) AS total,
                       COUNT(a.id) FILTER (WHERE a.status = 'NO_SHOW') AS no_shows,
                       COUNT(a.id) FILTER (WHERE a.status = 'CANCELLED') AS cancelled,
                       COUNT(a.id) FILTER (WHERE a.status = 'COMPLETED') AS completed,
                       COUNT(a.id) FILTER (WHERE a.status = 'BOOKED') AS booked,
                       COUNT(a.id) FILTER (WHERE a.status = 'CHECKED_IN') AS checked_in,
                       COUNT(a.id) FILTER (WHERE a.status = 'ARRIVED') AS arrived,
                       COUNT(a.id) FILTER (WHERE a.status = 'IN_CONSULTATION') AS in_consultation,
                       COUNT(a.id) FILTER (WHERE s.consultation_type = 'PHYSICAL') AS physical,
                       COUNT(a.id) FILTER (WHERE s.consultation_type = 'ONLINE') AS online,
                       COUNT(a.id) FILTER (WHERE a.status <> 'CANCELLED' AND a.payment_status <> 'PAID') AS unpaid,
                       COALESCE(SUM(s.fee_lkr) FILTER (WHERE a.status <> 'CANCELLED' AND a.payment_status <> 'PAID'), 0) AS unpaid_amount
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE s.session_date BETWEEN ? AND ?
                """;

        AppointmentMetrics metrics = jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new AppointmentMetrics(
                        rs.getLong("total"),
                        rs.getLong("no_shows"),
                        rs.getLong("cancelled"),
                        rs.getLong("completed"),
                        rs.getLong("booked"),
                        rs.getLong("checked_in"),
                        rs.getLong("arrived"),
                        rs.getLong("in_consultation"),
                        rs.getLong("physical"),
                        rs.getLong("online"),
                        rs.getLong("unpaid"),
                        rs.getBigDecimal("unpaid_amount") == null
                                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                                : rs.getBigDecimal("unpaid_amount").setScale(2, RoundingMode.HALF_UP)
                ),
                Date.valueOf(from),
                Date.valueOf(to)
        );

        return metrics != null
                ? metrics
                : new AppointmentMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
    }

    private SessionTodayMetrics sessionTodayMetrics(LocalDate today) {
        String sessionSql = """
                SELECT COUNT(s.id) AS sessions_today,
                       COUNT(s.id) FILTER (WHERE s.status IN ('OPEN', 'IN_PROGRESS')) AS active_sessions_today,
                       COUNT(s.id) FILTER (WHERE s.status = 'COMPLETED') AS completed_sessions_today,
                       COUNT(s.id) FILTER (WHERE s.status = 'CANCELLED') AS cancelled_sessions_today,
                       COUNT(DISTINCT s.doctor_id) FILTER (WHERE s.status <> 'CANCELLED') AS doctors_today,
                       COUNT(DISTINCT s.room_id) FILTER (WHERE s.status <> 'CANCELLED' AND s.room_id IS NOT NULL) AS rooms_today,
                       COALESCE(SUM(s.max_appointments) FILTER (WHERE s.status <> 'CANCELLED'), 0) AS total_capacity
                FROM doctor_sessions s
                WHERE s.session_date = ?
                """;

        SessionTodayRow row = jdbcTemplate.queryForObject(
                sessionSql,
                (rs, rowNum) -> new SessionTodayRow(
                        rs.getLong("sessions_today"),
                        rs.getLong("active_sessions_today"),
                        rs.getLong("completed_sessions_today"),
                        rs.getLong("cancelled_sessions_today"),
                        rs.getLong("doctors_today"),
                        rs.getLong("rooms_today"),
                        rs.getLong("total_capacity")
                ),
                Date.valueOf(today)
        );

        long sessionsToday = row != null ? row.sessionsToday : 0L;
        long activeSessionsToday = row != null ? row.activeSessionsToday : 0L;
        long completedSessionsToday = row != null ? row.completedSessionsToday : 0L;
        long cancelledSessionsToday = row != null ? row.cancelledSessionsToday : 0L;
        long doctorsToday = row != null ? row.doctorsToday : 0L;
        long roomsToday = row != null ? row.roomsToday : 0L;
        long totalCapacity = row != null ? row.totalCapacity : 0L;

        String capacitySql = """
                SELECT COUNT(a.id)
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE s.session_date = ?
                  AND s.status <> 'CANCELLED'
                  AND a.status <> 'CANCELLED'
                """;

        Long bookedCount = jdbcTemplate.queryForObject(
                capacitySql,
                Long.class,
                Date.valueOf(today)
        );
        long bookedCapacity = bookedCount != null ? bookedCount : 0L;

        double occupancyRate = totalCapacity <= 0
                ? 0.0
                : round(bookedCapacity * 100.0 / totalCapacity, 2);

        return new SessionTodayMetrics(
                sessionsToday,
                activeSessionsToday,
                completedSessionsToday,
                cancelledSessionsToday,
                doctorsToday,
                roomsToday,
                totalCapacity,
                bookedCapacity,
                occupancyRate
        );
    }

    private BigDecimal revenue(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COALESCE(SUM(p.amount), 0)
                FROM payments p
                JOIN appointments a ON a.id = p.appointment_id
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE p.status = 'COMPLETED'
                  AND s.session_date BETWEEN ? AND ?
                """;

        BigDecimal total = jdbcTemplate.queryForObject(
                sql,
                BigDecimal.class,
                Date.valueOf(from),
                Date.valueOf(to)
        );
        return total == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : total.setScale(2, RoundingMode.HALF_UP);
    }

    private long completedPayments(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(p.id)
                FROM payments p
                JOIN appointments a ON a.id = p.appointment_id
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE p.status = 'COMPLETED'
                  AND s.session_date BETWEEN ? AND ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                Date.valueOf(from),
                Date.valueOf(to)
        );
        return count == null ? 0 : count;
    }

    private List<DoctorPerformance> topDoctors(
            LocalDate from,
            LocalDate to
    ) {
        String sql = """
                SELECT d.id,
                       d.first_name || ' ' || d.last_name AS doctor_name,
                       COUNT(a.id) AS appointments,
                       COALESCE(SUM(p.amount), 0) AS revenue
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                JOIN doctors d ON d.id = s.doctor_id
                LEFT JOIN payments p ON p.appointment_id = a.id
                    AND p.status = 'COMPLETED'
                WHERE s.session_date BETWEEN ? AND ?
                GROUP BY d.id, d.first_name, d.last_name
                ORDER BY appointments DESC, revenue DESC
                LIMIT 5
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DoctorPerformance(
                        rs.getObject("id", UUID.class),
                        rs.getString("doctor_name"),
                        rs.getLong("appointments"),
                        rs.getBigDecimal("revenue")
                                .setScale(2, RoundingMode.HALF_UP)
                ),
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }

    private List<DoctorRating> doctorRatings() {
        String sql = """
                SELECT d.id,
                       d.first_name || ' ' || d.last_name AS doctor_name,
                       AVG(r.rating) AS average_rating,
                       COUNT(r.id) AS review_count
                FROM doctors d
                JOIN reviews r ON r.doctor_id = d.id
                GROUP BY d.id, d.first_name, d.last_name
                ORDER BY average_rating DESC, review_count DESC
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DoctorRating(
                        rs.getObject("id", UUID.class),
                        rs.getString("doctor_name"),
                        round(rs.getDouble("average_rating"), 2),
                        rs.getLong("review_count")
                )
        );
    }

    private static LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value)
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record AppointmentMetrics(
            long total,
            long noShows,
            long cancelled,
            long completed,
            long booked,
            long checkedIn,
            long arrived,
            long inConsultation,
            long physical,
            long online,
            long unpaid,
            BigDecimal unpaidAmount
    ) {}

    private record SessionTodayRow(
            long sessionsToday,
            long activeSessionsToday,
            long completedSessionsToday,
            long cancelledSessionsToday,
            long doctorsToday,
            long roomsToday,
            long totalCapacity
    ) {}

    private record SessionTodayMetrics(
            long sessionsToday,
            long activeSessionsToday,
            long completedSessionsToday,
            long cancelledSessionsToday,
            long doctorsToday,
            long roomsToday,
            long totalCapacity,
            long bookedCapacity,
            double occupancyRate
    ) {}
}
