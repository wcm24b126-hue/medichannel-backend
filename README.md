# MediChannel – Backend

## Project Overview

MediChannel is a Hospital Channelling and Appointment Management System developed as a group project for IT404050 – Full Stack Development.

The backend provides REST APIs, authentication, business logic, database operations, and real-time communication.

## Technology Stack

- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- Spring WebSocket
- PostgreSQL
- Maven

## Deployment Architecture

- Backend hosting: Render
- Database hosting: Neon PostgreSQL
- Frontend hosting: Netlify

## User Roles

- CUSTOMER
- DOCTOR
- MANAGER
- ADMIN

## Main Modules

- Authentication and authorization
- User and role management
- Patient management
- Doctor and specialisation management
- Room and session management
- Appointment booking
- Queue management
- Online consultation chat
- Notifications
- Feedback and notices
- Audit logs

## Database

The system uses PostgreSQL hosted on Neon.

Database schema changes will be managed through Flyway migration files.

Never commit database passwords, connection strings, encryption keys, JWT secrets, or other credentials.

## Getting Started

### Run locally

1. Copy `.env.example` to `.env` and set the Neon development branch's direct
   (non-pooler) JDBC host, database role, password, and three distinct
   Base64-encoded 32-byte secrets. Do not commit `.env`.
2. Start the backend with the `dev` profile:

       SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

The backend listens on port 8080 by default. Flyway applies pending migrations
at application startup, so use the Neon `development` branch for local testing.
Do not point a first deployment at `production` until reviewing and validating
the pending migrations.

## Environment Variables

Create a local `.env` file from `.env.example` for development.

Example:

    DB_URL=jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require
    DB_USERNAME=YOUR_DATABASE_USERNAME
    DB_PASSWORD=YOUR_DATABASE_PASSWORD

These are example placeholders only. Use your own development credentials.

Do not commit `.env` or any file containing real credentials.

## Deploy to Render

The root `render.yaml` defines a Docker-based web service and uses
`/actuator/health` as its health check. In Render, create a Blueprint from this
repository and provide the prompted environment variables:

- `DB_URL`: JDBC URL for Neon database `neondb`, using the direct/non-pooler
  host and `sslmode=require`. The backend runs Flyway at startup, which should
  use a direct Neon connection.
- `DB_USERNAME` and `DB_PASSWORD`: credentials for a Neon role allowed to run
  the Flyway migrations and serve the application.
- `JWT_SECRET`, `ENCRYPTION_KEY`, and `HMAC_SECRET`: three separate
  Base64-encoded 32-byte secrets. Generate each independently with
  `openssl rand -base64 32`.
- `APP_FRONTEND_ORIGIN`: exact deployed frontend origin, without a path.

Render supplies `PORT`; Spring Boot binds to it automatically. The blueprint
uses the `prod` logging profile. Flyway will run every unapplied migration
included in the deployed artifact, so verify all required migration files are
committed and test them against the Neon `development` branch before deploying.
The free Render plan may suspend the service while idle; change the plan in
Render if always-on availability is required.

## Team Development

1. Clone the repository.
2. Create a feature branch for your assigned task.
3. Make your changes.
4. Commit and push your work if you have repository write access.
5. Submit a pull request for review.

## Project Repository

https://github.com/wcm24b126-hue/medichannel-backend
