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

Backend setup instructions will be added after the Spring Boot project is initialized.

## Environment Variables

Create a local `.env` file for development.

Example:

    DB_URL=jdbc:postgresql://YOUR_NEON_HOST/YOUR_DATABASE?sslmode=require
    DB_USERNAME=YOUR_DATABASE_USERNAME
    DB_PASSWORD=YOUR_DATABASE_PASSWORD

These are example placeholders only. Use your own development credentials.

Do not commit `.env` or any file containing real credentials.

## Team Development

1. Clone the repository.
2. Create a feature branch for your assigned task.
3. Make your changes.
4. Commit and push your work if you have repository write access.
5. Submit a pull request for review.

## Project Repository

https://github.com/wcm24b126-hue/medichannel-backend
