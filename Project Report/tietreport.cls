%% tietreport.cls -- stand-in for the TIET project report class.
%% Provides the same commands as the official template so the report
%% compiles. Replace with the official tietreport.cls if you have it.
\NeedsTeXFormat{LaTeX2e}
\ProvidesClass{tietreport}[2026/10/15 TIET project report (compatible stand-in)]

\LoadClass[12pt,a4paper,oneside]{report}

\RequirePackage[margin=1in]{geometry}
\RequirePackage[T1]{fontenc}
\RequirePackage{lmodern}
\RequirePackage{setspace}
\RequirePackage{titlesec}
\RequirePackage[hidelinks]{hyperref}

\onehalfspacing

% Compact chapter headings (no huge "Chapter 1" block)
\titleformat{\chapter}[hang]
  {\normalfont\LARGE\bfseries}{\thechapter}{1em}{}
\titlespacing*{\chapter}{0pt}{0pt}{1.5em}

% ---- Title-page fields ------------------------------------------------
\newcommand{\@titlepageheader}{}
\newcommand{\@projecttitle}{}
\newcommand{\@projectsubtitle}{}
\newcommand{\@titlepagesubtext}{}
\newcommand{\@advisorname}{}
\newcommand{\@titlepagefootertext}{}

\newcommand{\TitlePageHeader}[1]{\renewcommand{\@titlepageheader}{#1}}
\newcommand{\ProjectTitle}[1]{\renewcommand{\@projecttitle}{#1}}
\newcommand{\ProjectSubTitle}[1]{\renewcommand{\@projectsubtitle}{#1}}
\newcommand{\TitlePageSubText}[1]{\renewcommand{\@titlepagesubtext}{#1}}
\newcommand{\AdvisorName}[1]{\renewcommand{\@advisorname}{#1}}
\newcommand{\TitlePageFooterText}[1]{\renewcommand{\@titlepagefootertext}{#1}}

% ---- Title page -------------------------------------------------------
\renewcommand{\maketitle}{%
  \begin{titlepage}
    \centering
    {\large\bfseries \@titlepageheader\par}
    \vspace{2.5cm}
    {\Huge\bfseries \@projecttitle\par}
    \vspace{0.6cm}
    {\Large \@projectsubtitle\par}
    \vspace{2cm}
    {\large Submitted by\par}
    \vspace{0.4cm}
    {\large \begin{tabular}{l}\@author\end{tabular}\par}
    \vspace{0.8cm}
    {\large \@titlepagesubtext\par}
    \vspace{1.5cm}
    {\large Under the guidance of\par}
    \vspace{0.3cm}
    {\large\bfseries \@advisorname\par}
    \vfill
    {\@titlepagefootertext\par}
  \end{titlepage}
  \setcounter{page}{1}%
}
