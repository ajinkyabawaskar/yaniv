#!/bin/bash
# Run all tests: backend (Maven) + frontend (Playwright)

set -e

echo "=========================================="
echo "Running All Tests for Yanif"
echo "=========================================="

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT"
FRONTEND_DIR="$PROJECT_ROOT/frontend"

# Function to run backend tests
run_backend_tests() {
  echo -e "\n${YELLOW}Running Backend Tests (Maven)...${NC}"
  cd "$BACKEND_DIR"

  if ./mvnw test -q; then
    echo -e "${GREEN}✓ Backend tests passed${NC}"
    return 0
  else
    echo -e "${RED}✗ Backend tests failed${NC}"
    return 1
  fi
}

# Function to run frontend unit tests (Jest). Fast, no servers needed.
run_frontend_unit_tests() {
  echo -e "\n${YELLOW}Running Frontend Unit Tests (Jest)...${NC}"
  cd "$FRONTEND_DIR"

  if CI=true npm test -- --watchAll=false; then
    echo -e "${GREEN}✓ Frontend unit tests passed${NC}"
    return 0
  else
    echo -e "${RED}✗ Frontend unit tests failed${NC}"
    return 1
  fi
}

# Function to run frontend tests
run_frontend_tests() {
  echo -e "\n${YELLOW}Running Frontend E2E Tests (Playwright)...${NC}"
  cd "$FRONTEND_DIR"

  # Check if backend is running
  if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo -e "${YELLOW}Backend not running, starting it...${NC}"
    cd "$BACKEND_DIR"
    ./mvnw spring-boot:run > /tmp/backend.log 2>&1 &
    BACKEND_PID=$!
    cd "$FRONTEND_DIR"

    # Wait for backend to be ready
    echo "Waiting for backend..."
    for i in {1..30}; do
      if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
        echo "Backend ready!"
        break
      fi
      sleep 2
    done

    if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
      echo -e "${RED}Backend failed to start${NC}"
      kill $BACKEND_PID 2>/dev/null
      return 1
    fi
  else
    BACKEND_PID=""
  fi

  # Check if frontend is running
  if ! curl -s http://localhost:3000 > /dev/null 2>&1; then
    echo -e "${YELLOW}Frontend not running, starting it...${NC}"
    npm run start > /tmp/frontend.log 2>&1 &
    FRONTEND_PID=$!

    # Wait for frontend to be ready
    echo "Waiting for frontend..."
    for i in {1..60}; do
      if curl -s http://localhost:3000 > /dev/null 2>&1; then
        echo "Frontend ready!"
        break
      fi
      sleep 2
    done

    if ! curl -s http://localhost:3000 > /dev/null 2>&1; then
      echo -e "${RED}Frontend failed to start${NC}"
      [ -n "$BACKEND_PID" ] && kill $BACKEND_PID 2>/dev/null
      kill $FRONTEND_PID 2>/dev/null
      return 1
    fi
  else
    FRONTEND_PID=""
  fi

  # Run Playwright tests
  if npm run test:e2e; then
    echo -e "${GREEN}✓ Frontend E2E tests passed${NC}"
    RESULT=0
  else
    echo -e "${RED}✗ Frontend E2E tests failed${NC}"
    RESULT=1
  fi

  # Cleanup
  [ -n "$BACKEND_PID" ] && kill $BACKEND_PID 2>/dev/null
  [ -n "$FRONTEND_PID" ] && kill $FRONTEND_PID 2>/dev/null

  return $RESULT
}

# Main
echo "Project root: $PROJECT_ROOT"

# Run backend tests
if run_backend_tests; then
  BACKEND_RESULT=0
else
  BACKEND_RESULT=1
fi

# Run frontend unit tests (includes the shared rules contract)
if run_frontend_unit_tests; then
  UNIT_RESULT=0
else
  UNIT_RESULT=1
fi

# Run frontend tests
if run_frontend_tests; then
  FRONTEND_RESULT=0
else
  FRONTEND_RESULT=1
fi

# Summary
echo -e "\n=========================================="
echo "Test Summary"
echo "=========================================="
if [ $BACKEND_RESULT -eq 0 ]; then
  echo -e "${GREEN}Backend Tests: PASSED${NC}"
else
  echo -e "${RED}Backend Tests: FAILED${NC}"
fi

if [ $UNIT_RESULT -eq 0 ]; then
  echo -e "${GREEN}Frontend Unit Tests: PASSED${NC}"
else
  echo -e "${RED}Frontend Unit Tests: FAILED${NC}"
fi

if [ $FRONTEND_RESULT -eq 0 ]; then
  echo -e "${GREEN}Frontend E2E Tests: PASSED${NC}"
else
  echo -e "${RED}Frontend E2E Tests: FAILED${NC}"
fi

if [ $BACKEND_RESULT -eq 0 ] && [ $UNIT_RESULT -eq 0 ] && [ $FRONTEND_RESULT -eq 0 ]; then
  echo -e "\n${GREEN}All tests passed!${NC}"
  exit 0
else
  echo -e "\n${RED}Some tests failed${NC}"
  exit 1
fi