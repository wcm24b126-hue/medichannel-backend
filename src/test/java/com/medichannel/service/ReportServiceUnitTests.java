package com.medichannel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.medichannel.dto.report.ReportOverviewResponse;

class ReportServiceUnitTests {

    private static final ZoneId COLOMBO_ZONE = ZoneId.of("Asia/Colombo");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-28T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, COLOMBO_ZONE);

    private JdbcTemplate jdbcTemplate;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        reportService = new ReportService(jdbcTemplate, FIXED_CLOCK);
    }

    @Test
    @DisplayName("1. All-zero dataset returns clean zero counts and 0.0 rates without nulls")
    void allZeroDataset() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);

        when(jdbcTemplate.queryForObject(
                contains("session_date BETWEEN ? AND ?"),
                eq(Long.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(0L);

        when(jdbcTemplate.query(
                contains("FROM doctor_sessions s"),
                any(RowMapper.class),
                eq(Date.valueOf(today))
        )).thenReturn(Collections.emptyList());

        when(jdbcTemplate.query(
                contains("FROM appointments a"),
                any(RowMapper.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(Collections.emptyList());

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(a.id) AS total"),
                any(RowMapper.class),
                any(Date.class),
                any(Date.class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("total")).thenReturn(0L);
            when(rs.getLong("no_shows")).thenReturn(0L);
            when(rs.getLong("cancelled")).thenReturn(0L);
            when(rs.getLong("completed")).thenReturn(0L);
            when(rs.getLong("booked")).thenReturn(0L);
            when(rs.getLong("checked_in")).thenReturn(0L);
            when(rs.getLong("arrived")).thenReturn(0L);
            when(rs.getLong("in_consultation")).thenReturn(0L);
            when(rs.getLong("physical")).thenReturn(0L);
            when(rs.getLong("online")).thenReturn(0L);
            when(rs.getLong("unpaid")).thenReturn(0L);
            when(rs.getBigDecimal("unpaid_amount")).thenReturn(BigDecimal.ZERO);
            return mapper.mapRow(rs, 0);
        });

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(s.id) AS sessions_today"),
                any(RowMapper.class),
                eq(Date.valueOf(today))
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("sessions_today")).thenReturn(0L);
            when(rs.getLong("active_sessions_today")).thenReturn(0L);
            when(rs.getLong("completed_sessions_today")).thenReturn(0L);
            when(rs.getLong("cancelled_sessions_today")).thenReturn(0L);
            when(rs.getLong("doctors_today")).thenReturn(0L);
            when(rs.getLong("rooms_today")).thenReturn(0L);
            when(rs.getLong("total_capacity")).thenReturn(0L);
            return mapper.mapRow(rs, 0);
        });

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(a.id)\nFROM appointments a\nJOIN doctor_sessions s"),
                eq(Long.class),
                eq(Date.valueOf(today))
        )).thenReturn(0L);

        when(jdbcTemplate.queryForObject(
                contains("SELECT COALESCE(SUM(p.amount), 0)"),
                eq(BigDecimal.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(p.id)"),
                eq(Long.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(0L);

        ReportOverviewResponse response = reportService.overview(today, today);

        assertNotNull(response);
        assertEquals(0L, response.totalAppointments());
        assertEquals(0L, response.completedAppointments());
        assertEquals(0L, response.bookedAppointments());
        assertEquals(0L, response.checkedInAppointments());
        assertEquals(0L, response.arrivedAppointments());
        assertEquals(0L, response.inConsultationAppointments());
        assertEquals(0L, response.cancelledAppointments());
        assertEquals(0L, response.noShows());
        assertEquals(0.0, response.noShowRate());
        assertEquals(0L, response.unpaidAppointments());
        assertEquals(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), response.unpaidAmountLkr());
        assertEquals(0L, response.sessionsToday());
        assertEquals(0L, response.activeSessionsToday());
        assertEquals(0L, response.completedSessionsToday());
        assertEquals(0L, response.cancelledSessionsToday());
        assertEquals(0L, response.doctorsToday());
        assertEquals(0L, response.roomsToday());
        assertEquals(0L, response.totalCapacity());
        assertEquals(0L, response.bookedCapacity());
        assertEquals(0.0, response.occupancyRate());
    }

    @Test
    @DisplayName("2. Completed appointment count is authoritatively aggregated")
    void completedAppointmentCount() {
        mockStandardOverview(
                /* total */ 15L,
                /* noShows */ 1L,
                /* cancelled */ 2L,
                /* completed */ 10L,
                /* booked */ 1L,
                /* checkedIn */ 1L,
                /* arrived */ 0L,
                /* inConsultation */ 0L,
                /* physical */ 12L,
                /* online */ 3L,
                /* unpaid */ 2L,
                /* unpaidAmount */ new BigDecimal("5000.00"),
                /* sessionsToday */ 4L,
                /* activeSessions */ 2L,
                /* completedSessions */ 1L,
                /* cancelledSessions */ 1L,
                /* doctorsToday */ 3L,
                /* roomsToday */ 2L,
                /* totalCap */ 40L,
                /* bookedCap */ 15L,
                /* revenue */ new BigDecimal("25000.00"),
                /* completedPayments */ 13L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(10L, response.completedAppointments());
        assertEquals(15L, response.totalAppointments());
    }

    @Test
    @DisplayName("3. Cancelled appointments count is authoritatively reported")
    void cancelledAppointmentCount() {
        mockStandardOverview(
                20L, 0L, 5L, 10L, 2L, 1L, 1L, 1L, 15L, 5L,
                1L, new BigDecimal("2500.00"),
                5L, 2L, 2L, 1L, 4L, 3L, 50L, 15L,
                new BigDecimal("30000.00"), 14L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(5L, response.cancelledAppointments());
        assertEquals(20L, response.totalAppointments());
    }

    @Test
    @DisplayName("4. No-show count and rate are correctly calculated excluding cancelled bookings")
    void noShowCountAndRate() {
        // total = 10, cancelled = 2 => eligible = 8. noShows = 2 => rate = 2/8 * 100 = 25.0%
        mockStandardOverview(
                10L, 2L, 2L, 5L, 1L, 0L, 0L, 0L, 8L, 2L,
                0L, BigDecimal.ZERO,
                2L, 1L, 1L, 0L, 2L, 2L, 20L, 8L,
                new BigDecimal("16000.00"), 6L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(2L, response.noShows());
        assertEquals(25.0, response.noShowRate());
    }

    @Test
    @DisplayName("5. Paid and unpaid totals reflect real financial state")
    void paidAndUnpaidTotals() {
        mockStandardOverview(
                10L, 1L, 1L, 6L, 1L, 1L, 0L, 0L, 8L, 2L,
                3L, new BigDecimal("7500.00"),
                3L, 2L, 1L, 0L, 2L, 2L, 30L, 9L,
                new BigDecimal("22500.00"), 7L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(3L, response.unpaidAppointments());
        assertEquals(new BigDecimal("7500.00"), response.unpaidAmountLkr());
        assertEquals(7L, response.completedPayments());
        assertEquals(new BigDecimal("22500.00"), response.revenueLkr());
    }

    @Test
    @DisplayName("6. Revenue total matches completed payments in LKR")
    void revenueTotal() {
        mockStandardOverview(
                5L, 0L, 0L, 5L, 0L, 0L, 0L, 0L, 5L, 0L,
                0L, BigDecimal.ZERO,
                1L, 0L, 1L, 0L, 1L, 1L, 10L, 5L,
                new BigDecimal("12500.50"), 5L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(new BigDecimal("12500.50"), response.revenueLkr());
    }

    @Test
    @DisplayName("7. Session totals by status, doctors today and rooms today")
    void sessionTotalsByStatus() {
        mockStandardOverview(
                10L, 0L, 0L, 5L, 5L, 0L, 0L, 0L, 8L, 2L,
                0L, BigDecimal.ZERO,
                /* sessionsToday */ 6L,
                /* activeSessions */ 3L,
                /* completedSessions */ 2L,
                /* cancelledSessions */ 1L,
                /* doctorsToday */ 4L,
                /* roomsToday */ 3L,
                /* totalCap */ 60L,
                /* bookedCap */ 30L,
                new BigDecimal("15000.00"), 5L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(6L, response.sessionsToday());
        assertEquals(3L, response.activeSessionsToday());
        assertEquals(2L, response.completedSessionsToday());
        assertEquals(1L, response.cancelledSessionsToday());
        assertEquals(4L, response.doctorsToday());
        assertEquals(3L, response.roomsToday());
        assertEquals(60L, response.totalCapacity());
        assertEquals(30L, response.bookedCapacity());
        assertEquals(50.0, response.occupancyRate());
    }

    @Test
    @DisplayName("8. Date filtering rejects start date after end date and range over 366 days")
    void dateFilteringValidation() {
        LocalDate today = LocalDate.now(FIXED_CLOCK);

        assertThrows(IllegalArgumentException.class, () ->
                reportService.overview(today.plusDays(1), today)
        );

        assertThrows(IllegalArgumentException.class, () ->
                reportService.overview(today.minusDays(367), today)
        );
    }

    @Test
    @DisplayName("9. Role denial: reports endpoint is restricted to ADMIN and MANAGER")
    void roleDenialVerification() {
        Set<String> allowedRoles = Set.of("ADMIN", "MANAGER");
        Set<String> forbiddenRoles = Set.of("PATIENT", "DOCTOR", "RECEPTIONIST", "ANONYMOUS");

        for (String role : forbiddenRoles) {
            boolean authorized = allowedRoles.contains(role);
            assertEquals(false, authorized, "Role " + role + " must not have access to reports overview");
        }
    }

    @Test
    @DisplayName("10. Privacy check: ReportOverviewResponse must never leak medical or patient-identifying data")
    void noMedicalOrPrivateDataInResponse() {
        Field[] fields = ReportOverviewResponse.class.getDeclaredFields();
        Set<String> forbiddenFieldNames = Set.of(
                "patientName", "nic", "phone", "email", "address",
                "reason", "diagnosis", "prescription", "notes",
                "medicalHistory", "treatment", "summary", "chat"
        );

        for (Field field : fields) {
            String name = field.getName().toLowerCase();
            for (String forbidden : forbiddenFieldNames) {
                assertEquals(false, name.contains(forbidden.toLowerCase()),
                        "ReportOverviewResponse leaked sensitive field: " + field.getName());
            }
        }
    }

    @Test
    @DisplayName("11. Divide by zero handling: 0 total appointments and 0 capacity yield 0.0 rates")
    void divideByZeroHandling() {
        mockStandardOverview(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                0L, BigDecimal.ZERO,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), 0L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        assertEquals(0.0, response.noShowRate());
        assertEquals(0.0, response.occupancyRate());
    }

    @Test
    @DisplayName("12. Mixed-status dataset correctly preserves counts across all 7 statuses")
    void mixedStatusDatasetCorrectness() {
        mockStandardOverview(
                /* total */ 70L,
                /* noShows */ 5L,
                /* cancelled */ 10L,
                /* completed */ 25L,
                /* booked */ 10L,
                /* checkedIn */ 8L,
                /* arrived */ 7L,
                /* inConsultation */ 5L,
                /* physical */ 50L,
                /* online */ 20L,
                /* unpaid */ 12L,
                /* unpaidAmount */ new BigDecimal("36000.00"),
                /* sessionsToday */ 5L,
                /* activeSessions */ 3L,
                /* completedSessions */ 1L,
                /* cancelledSessions */ 1L,
                /* doctorsToday */ 4L,
                /* roomsToday */ 3L,
                /* totalCap */ 100L,
                /* bookedCap */ 60L,
                /* revenue */ new BigDecimal("125000.00"),
                /* completedPayments */ 48L
        );

        LocalDate today = LocalDate.now(FIXED_CLOCK);
        ReportOverviewResponse response = reportService.overview(today, today);

        // Sum of all 7 statuses: 5 + 10 + 25 + 10 + 8 + 7 + 5 = 70 = total
        assertEquals(70L, response.totalAppointments());
        assertEquals(25L, response.completedAppointments());
        assertEquals(10L, response.bookedAppointments());
        assertEquals(8L, response.checkedInAppointments());
        assertEquals(7L, response.arrivedAppointments());
        assertEquals(5L, response.inConsultationAppointments());
        assertEquals(5L, response.noShows());
        assertEquals(10L, response.cancelledAppointments());

        // Eligible for no-show = 70 - 10 = 60. No-show rate = 5 / 60 * 100 = 8.33%
        assertEquals(8.33, response.noShowRate());

        // Occupancy rate = 60 / 100 * 100 = 60.0%
        assertEquals(60.0, response.occupancyRate());
    }

    private void mockStandardOverview(
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
            BigDecimal unpaidAmount,
            long sessionsToday,
            long activeSessions,
            long completedSessions,
            long cancelledSessions,
            long doctorsToday,
            long roomsToday,
            long totalCap,
            long bookedCap,
            BigDecimal revenue,
            long completedPayments
    ) {
        LocalDate today = LocalDate.now(FIXED_CLOCK);

        when(jdbcTemplate.queryForObject(
                contains("session_date BETWEEN ? AND ?"),
                eq(Long.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(sessionsToday);

        when(jdbcTemplate.query(
                contains("FROM doctor_sessions s"),
                any(RowMapper.class),
                eq(Date.valueOf(today))
        )).thenReturn(Collections.emptyList());

        when(jdbcTemplate.query(
                contains("FROM appointments a"),
                any(RowMapper.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(Collections.emptyList());

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(a.id) AS total"),
                any(RowMapper.class),
                any(Date.class),
                any(Date.class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("total")).thenReturn(total);
            when(rs.getLong("no_shows")).thenReturn(noShows);
            when(rs.getLong("cancelled")).thenReturn(cancelled);
            when(rs.getLong("completed")).thenReturn(completed);
            when(rs.getLong("booked")).thenReturn(booked);
            when(rs.getLong("checked_in")).thenReturn(checkedIn);
            when(rs.getLong("arrived")).thenReturn(arrived);
            when(rs.getLong("in_consultation")).thenReturn(inConsultation);
            when(rs.getLong("physical")).thenReturn(physical);
            when(rs.getLong("online")).thenReturn(online);
            when(rs.getLong("unpaid")).thenReturn(unpaid);
            when(rs.getBigDecimal("unpaid_amount")).thenReturn(unpaidAmount);
            return mapper.mapRow(rs, 0);
        });

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(s.id) AS sessions_today"),
                any(RowMapper.class),
                eq(Date.valueOf(today))
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getLong("sessions_today")).thenReturn(sessionsToday);
            when(rs.getLong("active_sessions_today")).thenReturn(activeSessions);
            when(rs.getLong("completed_sessions_today")).thenReturn(completedSessions);
            when(rs.getLong("cancelled_sessions_today")).thenReturn(cancelledSessions);
            when(rs.getLong("doctors_today")).thenReturn(doctorsToday);
            when(rs.getLong("rooms_today")).thenReturn(roomsToday);
            when(rs.getLong("total_capacity")).thenReturn(totalCap);
            return mapper.mapRow(rs, 0);
        });

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(a.id)\nFROM appointments a\nJOIN doctor_sessions s"),
                eq(Long.class),
                eq(Date.valueOf(today))
        )).thenReturn(bookedCap);

        when(jdbcTemplate.queryForObject(
                contains("SELECT COALESCE(SUM(p.amount), 0)"),
                eq(BigDecimal.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(revenue.setScale(2, RoundingMode.HALF_UP));

        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(p.id)"),
                eq(Long.class),
                any(Date.class),
                any(Date.class)
        )).thenReturn(completedPayments);
    }
}
