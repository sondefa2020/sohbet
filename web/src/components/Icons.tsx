// Çizgi ikonlar (dekoratif; düğmelerin erişilebilir adı aria-label ile verilir).

type P = { size?: number };
const base = (size = 22) => ({
  width: size,
  height: size,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
});

export const ChatIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" />
  </svg>
);
export const LockIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <rect x="4" y="11" width="16" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </svg>
);
export const SearchIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </svg>
);
export const ClipIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <path d="m21 11-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 7" />
  </svg>
);
export const SendIcon = ({ size }: P) => (
  <svg {...base(size)} strokeWidth={2.2}>
    <path d="M5 12h13M13 6l6 6-6 6" />
  </svg>
);
export const BackIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <path d="M19 12H5M11 6l-6 6 6 6" />
  </svg>
);
export const SettingsIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1" />
  </svg>
);
export const KeyIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <circle cx="8" cy="15" r="4" />
    <path d="m10.8 12.2 8.7-8.7M16 6l2.5 2.5M14 8l2 2" />
  </svg>
);
export const FileIcon = ({ size }: P) => (
  <svg {...base(size)}>
    <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
    <path d="M14 3v6h6" />
  </svg>
);
export const LogoMark = ({ size = 42 }: P) => (
  <div className="logo" style={{ width: size, height: size }} aria-hidden="true">
    <svg width={size * 0.52} height={size * 0.52} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth={2.2} strokeLinejoin="round">
      <path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z" />
    </svg>
  </div>
);
