import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';

// Use relative URL if REACT_APP_API_URL is not set (empty), otherwise use the configured URL
// This allows the frontend to work with any host (network IP or localhost)
const getApiBaseUrl = (): string => {
  const configuredUrl = process.env.REACT_APP_API_URL;
  if (configuredUrl && configuredUrl.trim() !== '') {
    return configuredUrl;
  }
  // Use relative URL - will use the same host/port as the frontend
  return '/api/v1';
};

const API_BASE_URL = getApiBaseUrl();

export interface ApiClientConfig {
  baseURL?: string;
  timeout?: number;
}

class ApiClient {
  private client: AxiosInstance;

  constructor(config?: ApiClientConfig) {
    this.client = axios.create({
      baseURL: config?.baseURL || API_BASE_URL,
      timeout: config?.timeout || 10000,
    });

    // Add JWT token to requests
    this.client.interceptors.request.use((config) => {
      const token = localStorage.getItem('jwtToken');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      return config;
    });

    // Handle errors
    this.client.interceptors.response.use(
      (response) => response,
      (error) => {
        if (error.response?.status === 401) {
          // Unauthorized - clear auth and redirect
          localStorage.removeItem('jwtToken');
          localStorage.removeItem('userId');
          window.location.href = '/login';
        }
        return Promise.reject(error);
      }
    );
  }

  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.get<T>(url, config);
    return response.data;
  }

  async post<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.post<T>(url, data, config);
    return response.data;
  }

  async put<T>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.put<T>(url, data, config);
    return response.data;
  }

  async delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const response = await this.client.delete<T>(url, config);
    return response.data;
  }
}

export const apiClient = new ApiClient();

// User API
export const userApi = {
  resolve: (fingerprintHash: string, displayName?: string) =>
    apiClient.post('/users/resolve', { fingerprintHash, displayName }),
  
  profile: () => apiClient.get('/users/profile'),
  
  updateProfile: (displayName: string) =>
    apiClient.put('/users/profile', { displayName }),
};

// Friend API
export const friendApi = {
  sendRequest: (friendCode: string) =>
    apiClient.post('/friends/request', { friendCode }),
  
  respondRequest: (friendshipId: string, accepted: boolean) =>
    apiClient.post('/friends/respond', { friendshipId, accepted }),
  
  getList: () => apiClient.get('/friends/list'),
  
  getPendingRequests: () => apiClient.get('/friends/pending-requests'),
};

export const presenceApi = {
  markOnline: () => apiClient.post('/presence/online', {}),
};

// Room/Game API
export const gameApi = {
  createRoom: (targetScore?: number, maxPlayers?: number) =>
    apiClient.post('/rooms', { targetScore, maxPlayers }),
  
  joinRoom: (roomCode: string) =>
    apiClient.post(`/rooms/${roomCode}/join`, {}),
  
  getRoom: (roomCode: string) =>
    apiClient.get(`/rooms/code/${roomCode}`),
  
  getGameDetails: (gameId: string) =>
    apiClient.get(`/rooms/${gameId}`),
  
  getPlayers: (gameId: string) =>
    apiClient.get(`/rooms/${gameId}/players`),
};

export default apiClient;
