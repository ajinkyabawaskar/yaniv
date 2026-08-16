# Yanif Frontend

React-based frontend for the Yanif multiplayer card game platform.

## Features

- 🎮 **Real-time Gameplay**: WebSocket/STOMP integration for instant game updates
- 👥 **Friend Management**: Add friends, invite to games, presence tracking
- 🎯 **Game UI**: Card selection, hand management, score tracking
- 🔐 **Device-based Auth**: Browser fingerprinting + JWT tokens (no passwords)
- 📱 **Responsive Design**: Works on desktop, tablet, and mobile

## Tech Stack

- **React 18**: UI framework
- **TypeScript**: Static typing
- **Zustand**: State management
- **Axios**: HTTP client
- **STOMP.js**: WebSocket messaging
- **Tailwind CSS**: Styling

## Installation

```bash
# Install dependencies
npm install

# Set environment variables (copy from .env.example)
cp .env.example .env

# Start development server
npm start

# Build for production
npm run build
```

## Development

```bash
# Start React dev server (runs on http://localhost:3000)
npm start

# Backend API must be running on http://localhost:8080
# WebSocket endpoint: ws://localhost:8080/ws
```

## Build & Deployment

```bash
# Build for production
npm run build

# Output goes to build/ folder
# Maven copies to src/main/resources/static/

# Backend serves frontend from static/ folder
```

## Project Structure

```
frontend/
├── src/
│   ├── components/         # React UI components
│   ├── contexts/          # Context providers (Auth, WebSocket)
│   ├── stores/            # Zustand state stores
│   ├── utils/             # API client, fingerprinting
│   ├── views/             # Page-level components
│   ├── App.tsx            # Main app component
│   └── index.tsx          # Entry point
├── public/
│   └── index.html
├── package.json
├── .env                   # Environment variables
└── .gitignore
```

## Key Components

### Contexts
- **AuthContext**: Manages authentication state and persistence
- **StompContext**: WebSocket connection and STOMP messaging

### Views
- **AuthView**: Login screen with fingerprinting
- **MainView**: Home with lobby and friends sidebar
- **GameView**: Live game with table and scoreboard

### Components
- **FriendsSidebar**: Friend list with presence indicator
- **LobbyView**: Game creation and joining
- **GameView**: Game container
- **TableCanvas**: Card table UI
- **ScoreboardView**: Live scores
- **InviteNotificationToast**: Float notifications

### Stores
- **authStore**: User auth state (Zustand)
- **gameStore**: Game state (Zustand)

## Environment Variables

```
REACT_APP_API_URL=http://localhost:8080/api/v1
REACT_APP_WS_URL=http://localhost:8080/ws
```

## Fingerprinting Strategy

Browser fingerprinting uses combination of:
- User Agent
- Screen resolution
- Device capabilities
- Canvas fingerprint
- Timezone
- Storage availability
- IndexedDB support

Hash stored in localStorage for persistence.

## State Management

### Authentication (Zustand)
- User info: userId, displayName, friendCode
- JWT token
- Auth state (isAuthenticated, isLoading)

### Game (Zustand)
- Game ID and room code
- Current game state (LOBBY, IN_PROGRESS, etc.)
- Scores, eliminated players
- Player hand
- Deck count, top discard card

## WebSocket Message Flow

### Sending
- `/app/room/{roomId}/action` - Game action (discard/draw)
- `/app/room/{roomId}/call-yaniv` - Call Yaniv
- `/app/presence/heartbeat` - Keep alive
- `/app/game/invite` - Send game invite

### Receiving
- `/user/queue/game-state` - Game state updates
- `/user/queue/presence` - Friend presence updates
- `/user/queue/invites` - Game invitations

## Performance Tips

- Use `localStorage` for caching auth tokens and user preferences
- Debounce card selection in TableCanvas
- Use React.memo for card components in hand
- Lazy load game components

## Browser Support

- Chrome/Chromium 90+
- Firefox 88+
- Safari 14+
- Edge 90+

## Future Enhancements

- [ ] Sound effects and animations
- [ ] Game history/replays
- [ ] Tournament mode
- [ ] Leaderboards
- [ ] Mobile app (React Native)
- [ ] Accessibility improvements
- [ ] Internationalization (i18n)
