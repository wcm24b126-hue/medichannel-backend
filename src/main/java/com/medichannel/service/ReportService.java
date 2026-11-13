package com.medichannel.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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

        List<TodaySession> todaySessions = todaySessions(today);
        List<DailyAppointmentCount> perDay =
                appointmentsPerDay(rangeStart, rangeEnd);

        long physical = countByType(rangeStart, rangeEnd, "PHYSICAL");
        long online = countByType(rangeStart, rangeEnd, "ONLINE");

        long[] statusCounts = appointmentStatusCounts(
                rangeStart,
                rangeEnd
        );
        long total = statusCounts[0];
        long noShows = statusCounts[1];
        long cancelled = statusCounts[2];

        double noShowRate = total == 0
                ? 0.0
                : round(noShows * 100.0 / total, 2);

        BigDecimal revenue = revenue(rangeStart, rangeEnd);
        long completedPayments = completedPayments(rangeStart, rangeEnd);

        return new ReportOverviewResponse(
                rangeStart,
                rangeEnd,
                todaySessions,
                perDay,
                physical,
                online,
                total,
                noShows,
                cancelled,
                noShowRate,
                revenue,
                completedPayments,
                topDoctors(rangeStart, rangeEnd),
                doctorRatings()
        );
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
                         s.consultation_type, s.status, s.max_appointments
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
                        rs.getLong("completed")
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

    private long countByType(
            LocalDate from,
            LocalDate to,
            String type
    ) {
        String sql = """
                SELECT COUNT(a.id)
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE s.session_date BETWEEN ? AND ?
                  AND s.consultation_type = ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                Date.valueOf(from),
                Date.valueOf(to),
                type
        );
        return count == null ? 0 : count;
    }

    /**
     * Returns {total, noShows, cancelled} for the range.
     */
    private long[] appointmentStatusCounts(LocalDate from, LocalDate to) {
        String sql = """
                SELECT COUNT(a.id) AS total,
                       COUNT(a.id) FILTER (
                           WHERE a.status = 'NO_SHOW'
                       ) AS no_shows,
                       COUNT(a.id) FILTER (
                           WHERE a.status = 'CANCELLED'
                       ) AS cancelled
                FROM appointments a
                JOIN doctor_sessions s ON s.id = a.session_id
                WHERE s.session_date BETWEEN ? AND ?
                """;

        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new long[]{
                        rs.getLong("total"),
                        rs.getLong("no_shows"),
                        rs.getLong("cancelled")
                },
                Date.valueOf(from),
                Date.valueOf(to)
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
}
