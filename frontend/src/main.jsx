import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.jsx'
import { AuthProvider } from './auth.jsx'
import { ToastProvider } from './components/Toast.jsx'
import { SavedProvider } from './saved.jsx'
import './styles.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ToastProvider>
      <AuthProvider>
        <SavedProvider>
          <App />
        </SavedProvider>
      </AuthProvider>
    </ToastProvider>
  </React.StrictMode>,
)
