--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA public;


--
-- Name: assign_appointment_number(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.assign_appointment_number() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    allocated_number INTEGER;
BEGIN
    UPDATE doctor_sessions
    SET last_appointment_number = last_appointment_number + 1
    WHERE id = NEW.session_id
      AND status = 'OPEN'
      AND last_appointment_number < max_appointments
    RETURNING last_appointment_number
    INTO allocated_number;

    IF NOT FOUND THEN
        RAISE EXCEPTION
            'Session does not exist, is not open, or is fully booked'
            USING ERRCODE = '23514';
    END IF;

    NEW.appointment_number := allocated_number;

    RETURN NEW;
END;
$$;


--
-- Name: prevent_session_overlap(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.prevent_session_overlap() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status <> 'CANCELLED' THEN

        -- Lock scheduling for this doctor.
        PERFORM pg_advisory_xact_lock(
            hashtextextended(
                'medichannel:doctor:' || NEW.doctor_id::text,
                0
            )
        );

        -- Reject overlapping sessions for the same doctor.
        IF EXISTS (
            SELECT 1
            FROM doctor_sessions existing
            WHERE existing.doctor_id = NEW.doctor_id
              AND existing.id IS DISTINCT FROM NEW.id
              AND existing.status <> 'CANCELLED'
              AND (existing.session_date + existing.start_time)
                    < (NEW.session_date + NEW.end_time)
              AND (existing.session_date + existing.end_time)
                    > (NEW.session_date + NEW.start_time)
        ) THEN
            RAISE EXCEPTION
                'Doctor already has an overlapping session'
                USING ERRCODE = '23P01';
        END IF;

        -- If a physical room is assigned, lock scheduling for that room.
        IF NEW.room_id IS NOT NULL THEN

            PERFORM pg_advisory_xact_lock(
                hashtextextended(
                    'medichannel:room:' || NEW.room_id::text,
                    0
                )
            );

            -- Reject overlapping sessions in the same room.
            IF EXISTS (
                SELECT 1
                FROM doctor_sessions existing
                WHERE existing.room_id = NEW.room_id
                  AND existing.id IS DISTINCT FROM NEW.id
                  AND existing.status <> 'CANCELLED'
                  AND (existing.session_date + existing.start_time)
                        < (NEW.session_date + NEW.end_time)
                  AND (existing.session_date + existing.end_time)
                        > (NEW.session_date + NEW.start_time)
            ) THEN
                RAISE EXCEPTION
                    'Room is already assigned to an overlapping session'
                    USING ERRCODE = '23P01';
            END IF;

        END IF;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: protect_appointment_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_appointment_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'Appointments cannot be permanently deleted'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.session_id IS DISTINCT FROM NEW.session_id
       OR OLD.patient_id IS DISTINCT FROM NEW.patient_id
       OR OLD.appointment_number IS DISTINCT FROM NEW.appointment_number
       OR OLD.booking_reference IS DISTINCT FROM NEW.booking_reference
    THEN
        RAISE EXCEPTION
            'Appointment identity fields cannot be changed'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: api_rate_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_rate_limits (
    rate_key character varying(128) NOT NULL,
    window_bucket bigint NOT NULL,
    request_count integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: appointments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.appointments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    session_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    appointment_number integer NOT NULL,
    booking_reference uuid DEFAULT gen_random_uuid() NOT NULL,
    reason_encrypted text NOT NULL,
    status character varying(30) DEFAULT 'BOOKED'::character varying NOT NULL,
    payment_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    payment_method character varying(20),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    cancelled_at timestamp with time zone,
    arrived_at timestamp with time zone,
    consultation_started_at timestamp with time zone,
    consultation_completed_at timestamp with time zone,
    acceptance_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    doctor_accepted_at timestamp with time zone,
    CONSTRAINT chk_appointment_cancelled_at CHECK (((((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL)) OR (((status)::text <> 'CANCELLED'::text) AND (cancelled_at IS NULL)))),
    CONSTRAINT chk_appointment_consultation_time_order CHECK (((consultation_completed_at IS NULL) OR ((consultation_started_at IS NOT NULL) AND (consultation_completed_at >= consultation_started_at)))),
    CONSTRAINT chk_appointment_number CHECK (((appointment_number >= 1) AND (appointment_number <= 60))),
    CONSTRAINT chk_appointment_payment_method CHECK (((((payment_status)::text = 'PENDING'::text) AND (payment_method IS NULL)) OR (((payment_status)::text = 'PAID'::text) AND (payment_method IS NOT NULL) AND ((payment_method)::text = ANY ((ARRAY['CASH'::character varying, 'CARD'::character varying])::text[]))))),
    CONSTRAINT chk_appointment_payment_status CHECK (((payment_status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying])::text[]))),
    CONSTRAINT chk_appointment_status CHECK (((status)::text = ANY ((ARRAY['BOOKED'::character varying, 'ARRIVED'::character varying, 'IN_CONSULTATION'::character varying, 'COMPLETED'::character varying, 'NO_SHOW'::character varying, 'CANCELLED'::character varying])::text[])))
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    user_email character varying(255),
    user_role character varying(50),
    action character varying(255) NOT NULL,
    details text,
    ip_address character varying(100) DEFAULT '127.0.0.1'::character varying
);


--
-- Name: chat_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chat_messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    appointment_id uuid NOT NULL,
    sender_user_id uuid NOT NULL,
    sender_role character varying(20) NOT NULL,
    content text NOT NULL,
    sent_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_chat_content_length CHECK (((char_length(content) >= 1) AND (char_length(content) <= 2000))),
    CONSTRAINT chk_chat_sender_role CHECK (((sender_role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'DOCTOR'::character varying])::text[])))
);


--
-- Name: consultation_summaries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.consultation_summaries (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    appointment_id uuid NOT NULL,
    doctor_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    consultation_date date NOT NULL,
    symptoms_encrypted text,
    diagnosis_encrypted text NOT NULL,
    treatment_notes_encrypted text,
    prescription_encrypted text,
    follow_up_instructions_encrypted text,
    follow_up_date date,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_summary_follow_up CHECK (((follow_up_date IS NULL) OR (follow_up_date >= consultation_date)))
);


--
-- Name: doctor_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.doctor_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    doctor_id uuid NOT NULL,
    room_id uuid,
    session_date date NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    consultation_type character varying(20) NOT NULL,
    max_appointments integer NOT NULL,
    status character varying(20) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    minutes_per_patient integer DEFAULT 10 NOT NULL,
    fee_lkr numeric(10,2) DEFAULT 0.00 NOT NULL,
    delay_minutes integer DEFAULT 0 NOT NULL,
    last_appointment_number integer DEFAULT 0 NOT NULL,
    CONSTRAINT chk_consultation_type CHECK (((consultation_type)::text = ANY ((ARRAY['PHYSICAL'::character varying, 'ONLINE'::character varying])::text[]))),
    CONSTRAINT chk_session_appointment_counter CHECK (((last_appointment_number >= 0) AND (last_appointment_number <= max_appointments))),
    CONSTRAINT chk_session_capacity CHECK ((max_appointments > 0)),
    CONSTRAINT chk_session_delay CHECK ((delay_minutes >= 0)),
    CONSTRAINT chk_session_fee CHECK ((fee_lkr >= (0)::numeric)),
    CONSTRAINT chk_session_max_appointments CHECK (((max_appointments >= 1) AND (max_appointments <= 60))),
    CONSTRAINT chk_session_minutes_per_patient CHECK (((minutes_per_patient >= 5) AND (minutes_per_patient <= 30))),
    CONSTRAINT chk_session_room CHECK (((((consultation_type)::text = 'PHYSICAL'::text) AND (room_id IS NOT NULL)) OR (((consultation_type)::text = 'ONLINE'::text) AND (room_id IS NULL)))),
    CONSTRAINT chk_session_status CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'OPEN'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT chk_session_time CHECK ((end_time > start_time))
);


--
-- Name: doctors; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.doctors (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    specialisation_id uuid NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    registration_number character varying(100) NOT NULL,
    qualification character varying(255),
    biography text,
    consultation_fee numeric(10,2),
    is_available boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_consultation_fee CHECK (((consultation_fee IS NULL) OR (consultation_fee >= (0)::numeric)))
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: notices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notices (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title character varying(150) NOT NULL,
    body character varying(4000) NOT NULL,
    is_published boolean DEFAULT false NOT NULL,
    published_at timestamp with time zone,
    created_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_notice_body_length CHECK (((char_length((body)::text) >= 1) AND (char_length((body)::text) <= 4000))),
    CONSTRAINT chk_notice_publish_state CHECK ((((is_published = false) AND (published_at IS NULL)) OR ((is_published = true) AND (published_at IS NOT NULL)))),
    CONSTRAINT chk_notice_title_length CHECK (((char_length((title)::text) >= 1) AND (char_length((title)::text) <= 150)))
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    appointment_id uuid,
    type character varying(40) NOT NULL,
    title character varying(150) NOT NULL,
    message character varying(1000) NOT NULL,
    is_read boolean DEFAULT false NOT NULL,
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_notification_read_state CHECK ((((is_read = false) AND (read_at IS NULL)) OR ((is_read = true) AND (read_at IS NOT NULL)))),
    CONSTRAINT chk_notification_type CHECK (((type)::text = ANY ((ARRAY['APPOINTMENT_BOOKED'::character varying, 'APPOINTMENT_ARRIVED'::character varying, 'APPOINTMENT_CANCELLED'::character varying, 'APPOINTMENT_REMINDER'::character varying, 'PAYMENT_RECEIVED'::character varying, 'CONSULTATION_STARTED'::character varying, 'CONSULTATION_COMPLETED'::character varying, 'SUMMARY_AVAILABLE'::character varying, 'NEW_CHAT_MESSAGE'::character varying, 'REVIEW_RECEIVED'::character varying, 'ACCOUNT_STATUS_CHANGED'::character varying])::text[])))
);


--
-- Name: patients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.patients (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    first_name_encrypted text NOT NULL,
    last_name_encrypted text NOT NULL,
    phone_encrypted text,
    nic_lookup_hash character varying(64),
    date_of_birth_encrypted text,
    gender_encrypted text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    nic_encrypted text,
    address_encrypted text
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    appointment_id uuid NOT NULL,
    amount numeric(10,2) NOT NULL,
    currency character varying(3) DEFAULT 'LKR'::character varying NOT NULL,
    method character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'COMPLETED'::character varying NOT NULL,
    payment_reference character varying(64) NOT NULL,
    paid_at timestamp with time zone,
    recorded_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_payment_amount CHECK ((amount >= (0)::numeric)),
    CONSTRAINT chk_payment_currency CHECK (((currency)::text = 'LKR'::text)),
    CONSTRAINT chk_payment_method CHECK (((method)::text = ANY ((ARRAY['CASH'::character varying, 'CARD'::character varying])::text[]))),
    CONSTRAINT chk_payment_paid_at CHECK (((((status)::text = 'COMPLETED'::text) AND (paid_at IS NOT NULL)) OR ((status)::text <> 'COMPLETED'::text))),
    CONSTRAINT chk_payment_record_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'REFUNDED'::character varying])::text[])))
);


--
-- Name: reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reviews (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    appointment_id uuid NOT NULL,
    doctor_id uuid NOT NULL,
    patient_id uuid NOT NULL,
    rating integer NOT NULL,
    comment character varying(1000),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    moderation_status character varying(20) DEFAULT 'APPROVED'::character varying NOT NULL,
    CONSTRAINT chk_review_comment_length CHECK (((comment IS NULL) OR ((char_length((comment)::text) >= 1) AND (char_length((comment)::text) <= 1000)))),
    CONSTRAINT chk_review_moderation_status CHECK (((moderation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'HIDDEN'::character varying])::text[]))),
    CONSTRAINT chk_review_rating CHECK (((rating >= 1) AND (rating <= 5)))
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(30) NOT NULL,
    description character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_role_name CHECK (((name)::text = ANY ((ARRAY['ADMIN'::character varying, 'MANAGER'::character varying, 'DOCTOR'::character varying, 'CUSTOMER'::character varying])::text[])))
);


--
-- Name: rooms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rooms (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    room_number character varying(30) NOT NULL,
    name character varying(100) NOT NULL,
    location character varying(255),
    room_type character varying(30) DEFAULT 'CONSULTATION'::character varying NOT NULL,
    capacity integer DEFAULT 1 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_room_capacity CHECK ((capacity > 0)),
    CONSTRAINT chk_room_type CHECK (((room_type)::text = ANY ((ARRAY['CONSULTATION'::character varying, 'PROCEDURE'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: specialisations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.specialisations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500),
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    role_id uuid NOT NULL,
    email character varying(254) NOT NULL,
    password_hash character varying(255) NOT NULL,
    account_status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    email_verified boolean DEFAULT false NOT NULL,
    failed_login_attempts integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    token_version integer DEFAULT 1 NOT NULL,
    CONSTRAINT chk_account_status CHECK (((account_status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'LOCKED'::character varying, 'SUSPENDED'::character varying, 'DISABLED'::character varying])::text[]))),
    CONSTRAINT chk_failed_login_attempts CHECK ((failed_login_attempts >= 0)),
    CONSTRAINT chk_locked_until_status CHECK (((locked_until IS NULL) OR ((account_status)::text = 'LOCKED'::text))),
    CONSTRAINT chk_users_email_normalized CHECK (((length(btrim((email)::text)) > 0) AND ((email)::text = lower(btrim((email)::text)))))
);


--
-- Name: appointments appointments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT appointments_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: chat_messages chat_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);


--
-- Name: consultation_summaries consultation_summaries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultation_summaries
    ADD CONSTRAINT consultation_summaries_pkey PRIMARY KEY (id);


--
-- Name: doctor_sessions doctor_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctor_sessions
    ADD CONSTRAINT doctor_sessions_pkey PRIMARY KEY (id);


--
-- Name: doctors doctors_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_pkey PRIMARY KEY (id);


--
-- Name: doctors doctors_registration_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_registration_number_key UNIQUE (registration_number);


--
-- Name: doctors doctors_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT doctors_user_id_key UNIQUE (user_id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: notices notices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notices
    ADD CONSTRAINT notices_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: patients patients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT patients_pkey PRIMARY KEY (id);


--
-- Name: patients patients_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT patients_user_id_key UNIQUE (user_id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: api_rate_limits pk_api_rate_limits; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_rate_limits
    ADD CONSTRAINT pk_api_rate_limits PRIMARY KEY (rate_key, window_bucket);


--
-- Name: reviews reviews_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);


--
-- Name: roles roles_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_name_key UNIQUE (name);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: rooms rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rooms
    ADD CONSTRAINT rooms_pkey PRIMARY KEY (id);


--
-- Name: rooms rooms_room_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rooms
    ADD CONSTRAINT rooms_room_number_key UNIQUE (room_number);


--
-- Name: specialisations specialisations_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.specialisations
    ADD CONSTRAINT specialisations_name_key UNIQUE (name);


--
-- Name: specialisations specialisations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.specialisations
    ADD CONSTRAINT specialisations_pkey PRIMARY KEY (id);


--
-- Name: appointments uq_appointment_booking_reference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT uq_appointment_booking_reference UNIQUE (booking_reference);


--
-- Name: appointments uq_appointment_session_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT uq_appointment_session_number UNIQUE (session_id, appointment_number);


--
-- Name: payments uq_payments_reference; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payments_reference UNIQUE (payment_reference);


--
-- Name: reviews uq_review_appointment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT uq_review_appointment UNIQUE (appointment_id);


--
-- Name: consultation_summaries uq_summary_appointment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultation_summaries
    ADD CONSTRAINT uq_summary_appointment UNIQUE (appointment_id);


--
-- Name: users uq_users_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_appointments_patient_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appointments_patient_created ON public.appointments USING btree (patient_id, created_at DESC);


--
-- Name: idx_appointments_payment_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appointments_payment_status ON public.appointments USING btree (payment_status, created_at);


--
-- Name: idx_appointments_session_status_number; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_appointments_session_status_number ON public.appointments USING btree (session_id, status, appointment_number);


--
-- Name: idx_audit_logs_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_logs_created_at ON public.audit_logs USING btree (created_at DESC);


--
-- Name: idx_chat_messages_appointment_sent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_appointment_sent ON public.chat_messages USING btree (appointment_id, sent_at);


--
-- Name: idx_chat_messages_sender_sent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chat_messages_sender_sent ON public.chat_messages USING btree (sender_user_id, sent_at DESC);


--
-- Name: idx_doctors_specialisation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doctors_specialisation_id ON public.doctors USING btree (specialisation_id);


--
-- Name: idx_notices_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notices_created ON public.notices USING btree (created_at DESC);


--
-- Name: idx_notices_published; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notices_published ON public.notices USING btree (published_at DESC) WHERE (is_published = true);


--
-- Name: idx_notifications_appointment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_appointment ON public.notifications USING btree (appointment_id) WHERE (appointment_id IS NOT NULL);


--
-- Name: idx_notifications_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_user_created ON public.notifications USING btree (user_id, created_at DESC);


--
-- Name: idx_notifications_user_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_user_unread ON public.notifications USING btree (user_id) WHERE (is_read = false);


--
-- Name: idx_payments_appointment_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payments_appointment_created ON public.payments USING btree (appointment_id, created_at DESC);


--
-- Name: idx_payments_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payments_status_created ON public.payments USING btree (status, created_at DESC);


--
-- Name: idx_rate_limits_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rate_limits_window ON public.api_rate_limits USING btree (window_bucket);


--
-- Name: idx_reviews_doctor_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_doctor_created ON public.reviews USING btree (doctor_id, created_at DESC);


--
-- Name: idx_reviews_moderation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_moderation ON public.reviews USING btree (moderation_status, created_at DESC);


--
-- Name: idx_reviews_patient_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reviews_patient_created ON public.reviews USING btree (patient_id, created_at DESC);


--
-- Name: idx_rooms_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rooms_active ON public.rooms USING btree (is_active);


--
-- Name: idx_sessions_date_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_date_status ON public.doctor_sessions USING btree (session_date, status);


--
-- Name: idx_sessions_doctor_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_doctor_date ON public.doctor_sessions USING btree (doctor_id, session_date);


--
-- Name: idx_sessions_doctor_date_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_doctor_date_status ON public.doctor_sessions USING btree (doctor_id, session_date, status);


--
-- Name: idx_sessions_room_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_room_date ON public.doctor_sessions USING btree (room_id, session_date);


--
-- Name: idx_sessions_room_date_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sessions_room_date_status ON public.doctor_sessions USING btree (room_id, session_date, status);


--
-- Name: idx_summaries_doctor_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_summaries_doctor_date ON public.consultation_summaries USING btree (doctor_id, consultation_date DESC);


--
-- Name: idx_summaries_patient_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_summaries_patient_date ON public.consultation_summaries USING btree (patient_id, consultation_date DESC);


--
-- Name: idx_users_account_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_account_status ON public.users USING btree (account_status);


--
-- Name: idx_users_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role_id ON public.users USING btree (role_id);


--
-- Name: uq_active_appointment_patient_session; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_active_appointment_patient_session ON public.appointments USING btree (session_id, patient_id) WHERE ((status)::text = ANY ((ARRAY['BOOKED'::character varying, 'ARRIVED'::character varying, 'IN_CONSULTATION'::character varying])::text[]));


--
-- Name: uq_completed_payment_per_appointment; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_completed_payment_per_appointment ON public.payments USING btree (appointment_id) WHERE ((status)::text = 'COMPLETED'::text);


--
-- Name: uq_patients_nic_lookup_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_patients_nic_lookup_hash ON public.patients USING btree (nic_lookup_hash) WHERE (nic_lookup_hash IS NOT NULL);


--
-- Name: appointments trg_assign_appointment_number; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_assign_appointment_number BEFORE INSERT ON public.appointments FOR EACH ROW EXECUTE FUNCTION public.assign_appointment_number();


--
-- Name: doctor_sessions trg_prevent_session_overlap; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_prevent_session_overlap BEFORE INSERT OR UPDATE ON public.doctor_sessions FOR EACH ROW EXECUTE FUNCTION public.prevent_session_overlap();


--
-- Name: appointments trg_protect_appointment_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_protect_appointment_history BEFORE DELETE OR UPDATE ON public.appointments FOR EACH ROW EXECUTE FUNCTION public.protect_appointment_history();


--
-- Name: appointments fk_appointments_patient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT fk_appointments_patient FOREIGN KEY (patient_id) REFERENCES public.patients(id) ON DELETE RESTRICT;


--
-- Name: appointments fk_appointments_session; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.appointments
    ADD CONSTRAINT fk_appointments_session FOREIGN KEY (session_id) REFERENCES public.doctor_sessions(id) ON DELETE RESTRICT;


--
-- Name: chat_messages fk_chat_messages_appointment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT fk_chat_messages_appointment FOREIGN KEY (appointment_id) REFERENCES public.appointments(id) ON DELETE RESTRICT;


--
-- Name: chat_messages fk_chat_messages_sender; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: doctors fk_doctors_specialisation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT fk_doctors_specialisation FOREIGN KEY (specialisation_id) REFERENCES public.specialisations(id);


--
-- Name: doctors fk_doctors_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctors
    ADD CONSTRAINT fk_doctors_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: notices fk_notices_created_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notices
    ADD CONSTRAINT fk_notices_created_by FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;


--
-- Name: notifications fk_notifications_appointment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT fk_notifications_appointment FOREIGN KEY (appointment_id) REFERENCES public.appointments(id) ON DELETE SET NULL;


--
-- Name: notifications fk_notifications_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: patients fk_patients_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patients
    ADD CONSTRAINT fk_patients_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE RESTRICT;


--
-- Name: payments fk_payments_appointment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk_payments_appointment FOREIGN KEY (appointment_id) REFERENCES public.appointments(id) ON DELETE RESTRICT;


--
-- Name: payments fk_payments_recorded_by; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT fk_payments_recorded_by FOREIGN KEY (recorded_by) REFERENCES public.users(id) ON DELETE SET NULL;


--
-- Name: reviews fk_reviews_appointment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT fk_reviews_appointment FOREIGN KEY (appointment_id) REFERENCES public.appointments(id) ON DELETE RESTRICT;


--
-- Name: reviews fk_reviews_doctor; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT fk_reviews_doctor FOREIGN KEY (doctor_id) REFERENCES public.doctors(id) ON DELETE RESTRICT;


--
-- Name: reviews fk_reviews_patient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT fk_reviews_patient FOREIGN KEY (patient_id) REFERENCES public.patients(id) ON DELETE RESTRICT;


--
-- Name: doctor_sessions fk_sessions_doctor; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctor_sessions
    ADD CONSTRAINT fk_sessions_doctor FOREIGN KEY (doctor_id) REFERENCES public.doctors(id) ON DELETE RESTRICT;


--
-- Name: doctor_sessions fk_sessions_room; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.doctor_sessions
    ADD CONSTRAINT fk_sessions_room FOREIGN KEY (room_id) REFERENCES public.rooms(id) ON DELETE RESTRICT;


--
-- Name: consultation_summaries fk_summaries_appointment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultation_summaries
    ADD CONSTRAINT fk_summaries_appointment FOREIGN KEY (appointment_id) REFERENCES public.appointments(id) ON DELETE RESTRICT;


--
-- Name: consultation_summaries fk_summaries_doctor; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultation_summaries
    ADD CONSTRAINT fk_summaries_doctor FOREIGN KEY (doctor_id) REFERENCES public.doctors(id) ON DELETE RESTRICT;


--
-- Name: consultation_summaries fk_summaries_patient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.consultation_summaries
    ADD CONSTRAINT fk_summaries_patient FOREIGN KEY (patient_id) REFERENCES public.patients(id) ON DELETE RESTRICT;


--
-- Name: users fk_users_role; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- PostgreSQL database dump complete
--


