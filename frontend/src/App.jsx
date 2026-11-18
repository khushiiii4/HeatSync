import { Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { ToastProvider, useToast } from './components/Toast';
import { Shell } from './components/Shell';
import { hasRole } from './lib/roles';
import Login from './pages/Login';
import Overview from './pages/Overview';
import RackDetail from './pages/RackDetail';
import Workloads from './pages/Workloads';
import Cooling from './pages/Cooling';
import Events from './pages/Events';
import ControlRoom from './pages/ControlRoom';

function Protected({ minRole, children }) {
  const { user, role } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (minRole && !hasRole(role, minRole)) return <Navigate to="/" replace />;
  return children;
}

function Routed() {
  const toast = useToast();
  return (
    <AuthProvider onForbidden={() => toast.error('Not permitted', 'Your role does not allow that action.')}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route element={<Protected><Shell /></Protected>}>
          <Route index element={<Overview />} />
          <Route path="racks/:rackId" element={<RackDetail />} />
          <Route path="workloads" element={<Workloads />} />
          <Route path="cooling" element={<Cooling />} />
          <Route path="events" element={<Events />} />
          <Route path="control" element={<Protected minRole="OPERATOR"><ControlRoom /></Protected>} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}

export default function App() {
  return (
    <ToastProvider>
      <Routed />
    </ToastProvider>
  );
}
